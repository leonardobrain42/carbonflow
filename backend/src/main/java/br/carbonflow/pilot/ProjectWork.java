package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectWork {
    enum Kind { TRIAGE, INTAKE, GUIDE, DOSSIER, SECTION, REVIEW }
    enum State { NOT_STARTED, NEEDS_DATA, IN_PROGRESS, COMPLETED, IN_REVIEW, RETURNED, REQUIRES_RECHECK }
    record Destination(String area, String screen, String guide, String step) {}
    record Position(String area, String screen, String guide, String step) {}
    record Checkpoint(String area, String screen, String guide, String step, OffsetDateTime visitedAt) {}
    record Progress(int visible, int recorded, int needsAttention, int inReview, int actionable) {}
    record Task(String id, Kind kind, String title, State state, String reason, String responsible,
                int sourceVersion, Destination destination, boolean actionable) {}
    record View(String projectId, String title, String environment, String projectState, String role,
                int definitionVersion, String methodology, boolean methodologyConfigured,
                String nextTask, List<Task> tasks, List<String> recommendedTasks,
                Progress progress, Checkpoint resume, ProjectJourney.Profile journeyProfile,
                ProjectRequirementProjection.View diagnosis) {}
    private record Definition(String id, Kind kind, String title, String guide) {}
    private record Dossier(int revision, int intake) {}

    private final DynamicProjectScope scope;
    private final ProjectTriageWizard triage;
    private final ProjectGuides guides;
    private final PdReviews reviews;

    ProjectWork(DynamicProjectScope scope, ProjectTriageWizard triage, ProjectGuides guides, PdReviews reviews) {
        this.scope = scope;
        this.triage = triage;
        this.guides = guides;
        this.reviews = reviews;
    }

    View get(String cookie, String project) {
        return scope.read(cookie, project, "VIEW_JOURNEY", this::view);
    }

    /** Only a personal navigation marker. Never grants access or changes task state. */
    Checkpoint checkpoint(String cookie, String project, Position position) {
        return scope.write(cookie, project, "VIEW_JOURNEY", (connection, context) -> {
            if (!allowed(connection, context, position))
                throw PilotProblem.invalid("DESTINO_JORNADA_INVALIDO", "destination", "CF-UX-TECH-001",
                        "Este destino não pertence à jornada ou não está disponível para esta conta.");
            update(connection, """
                INSERT INTO app_project_journey_checkpoint(project_id,actor_id,area,screen,guide_id,step_id)
                VALUES(?,?,?,?,?,?)
                ON CONFLICT (project_id,actor_id) DO UPDATE SET
                   area=EXCLUDED.area, screen=EXCLUDED.screen,
                   guide_id=EXCLUDED.guide_id, step_id=EXCLUDED.step_id,
                   updated_at=statement_timestamp()
                """, context.id(), context.actor().id(), position.area(), position.screen(),
                    position.guide(), position.step());
            return readCheckpoint(connection, context);
        });
    }

    private Checkpoint readCheckpoint(Connection connection, DynamicProjectScope.Context context) throws SQLException {
        Checkpoint checkpoint = first(connection, """
            SELECT area,screen,guide_id,step_id,updated_at
            FROM app_project_journey_checkpoint WHERE project_id=? AND actor_id=?
            """, row -> new Checkpoint(row.getString(1),row.getString(2),row.getString(3),
                row.getString(4),row.getObject(5,OffsetDateTime.class)),
            context.id(),context.actor().id());
        if (checkpoint == null) return null;
        return allowed(connection, context, new Position(checkpoint.area(),
                checkpoint.screen(),checkpoint.guide(),checkpoint.step())) ? checkpoint : null;
    }

    private boolean allowed(Connection connection, DynamicProjectScope.Context ctx, Position location)
            throws SQLException {
        if (location == null || location.area() == null) return false;
        if ("triage".equals(location.area()))
            return ctx.owner() && location.screen() == null && location.guide() == null
                    && location.step() == null;
        if (!"technical".equals(location.area()) || location.screen() == null) return false;
        String screen=location.screen();
        var actions=ctx.actions();
        boolean permitted=switch(screen) {
            case "inicio","entender","preparar","fontes" ->
                actions.contains("VIEW_INTAKE");
            case "analisar","rede","direitos" -> actions.contains("VIEW_INTAKE")
                || actions.contains("VIEW_OPEN_APPLICABILITY");
            case "guias","linha-base","adicionalidade" -> actions.contains("VIEW_GUIDES");
            case "monitorar","estacoes","evidencias" -> actions.contains("VIEW_OPEN_MONITORING");
            case "quantificar" -> actions.contains("VIEW_OPEN_CALCULATION");
            case "redigir","revisar" -> actions.contains("VIEW_DOSSIER");
            default -> false;
        };
        if (!permitted) return false;
        if (Set.of("linha-base","adicionalidade","estacoes","evidencias","monitorar","quantificar")
                .contains(screen) &&
            ProjectJourney.profile(ProjectGuides.candidate(connection,ctx.id())) != ProjectJourney.VM0038)
            return false;
        if (location.guide()!=null) {
            if (!Set.of("guias","redigir","revisar").contains(screen)) return false;
            if (guides.catalog(connection,ctx).guides().stream()
                    .noneMatch(guide -> guide.id().equals(location.guide()))) return false;
        }
        if (location.step()==null) return true;
        return switch(screen) {
            case "monitorar" -> Set.of("inventario","leituras","qa","dossies").contains(location.step());
            case "linha-base" -> Set.of("contexto","frota","fatores","premissas","revisao","pd").contains(location.step());
            case "adicionalidade" -> Set.of("enquadramento","cronologia","direitos","revisao","pd")
                .contains(location.step());
            default -> false;
        };
    }

    private View view(Connection connection, DynamicProjectScope.Context context) throws SQLException {
        String project = context.id();
        var actions = context.actions();
        int definitionVersion = first(connection, """
                SELECT definition_version FROM app_project_journey_instance WHERE project_id = ?
                """, row -> row.getInt(1), project);
        String title = first(connection, "SELECT title FROM app_project WHERE id = ?", row -> row.getString(1), project);
        String candidate = ProjectGuides.candidate(connection, project);
        var method = ProjectJourney.profile(candidate);
        Map<String, ProjectGuides.Guide> visibleGuides = guides.catalog(connection, context).guides().stream()
                .collect(Collectors.toMap(ProjectGuides.Guide::id, Function.identity()));
        var definitions = query(connection, """
                SELECT task_id, kind, title, guide_id FROM app_journey_task_definition
                WHERE definition_version = ? ORDER BY position, task_id
                """, row -> new Definition(row.getString(1), Kind.valueOf(row.getString(2)),
                        row.getString(3), row.getString(4)), definitionVersion);
        int intake = actions.contains("VIEW_INTAKE") ? intakeVersion(connection, project) : 0;
        List<Task> tasks = new ArrayList<>();
        for (var definition : definitions) {
            var guide = definition.guide() == null ? null : visibleGuides.get(definition.guide());
            Task task = switch (definition.kind()) {
                case TRIAGE -> context.owner() ? triageTask(definition, triage.current(connection, context)) : null;
                case INTAKE -> actions.contains("VIEW_INTAKE") ? intakeTask(definition, intake, actions) : null;
                case GUIDE -> guide == null ? null : guideTask(connection, project, definition, guide, visibleGuides.keySet());
                case DOSSIER -> actions.contains("VIEW_DOSSIER")
                        ? dossierTask(connection, project, definition, intake, actions) : null;
                case SECTION, REVIEW -> guide != null && actions.contains("VIEW_DOSSIER")
                        ? documentTask(connection, context, definition, guide, visibleGuides.keySet()) : null;
            };
            if (task != null) tasks.add(task);
        }

        // Recommend only actionable, account-visible tasks; never infer normative
        // compliance or a review authorization from guide field completion.
        var candidates=tasks.stream().filter(Task::actionable)
            .sorted(Comparator.comparingInt(ProjectWork::priority)).toList();
        var recommended=candidates.stream().limit(3).map(Task::id).toList();
        int recorded=(int) tasks.stream().filter(t->t.state()==State.COMPLETED).count();
        int attention=(int) tasks.stream().filter(t->Set.of(State.NEEDS_DATA,
                State.RETURNED,State.REQUIRES_RECHECK).contains(t.state())).count();
        int review=(int) tasks.stream().filter(t->t.state()==State.IN_REVIEW).count();
        var progress=new Progress(tasks.size(),recorded,attention,review,candidates.size());
        return new View(project, title, context.environment(), context.project().state(), context.role(),
                definitionVersion, method == ProjectJourney.GENERAL ? candidate : method.title(),
                method != ProjectJourney.GENERAL, recommended.isEmpty()?null:recommended.get(0),
                List.copyOf(tasks),recommended,progress,readCheckpoint(connection,context),method,
                ProjectRequirements.assemble(connection, context));
    }

    private static int priority(Task task) {
        return switch(task.state()) {
            case RETURNED -> 0;
            case REQUIRES_RECHECK -> 1;
            case NEEDS_DATA -> 2;
            case IN_REVIEW -> 3;
            case IN_PROGRESS -> 4;
            case NOT_STARTED -> 5;
            case COMPLETED -> 6;
        };
    }

    private static Task triageTask(Definition definition, ProjectTriageWizard.View view) {
        State state = view.version() == 0 ? State.NOT_STARTED
                : view.pending().isEmpty() ? State.COMPLETED : State.NEEDS_DATA;
        return task(definition, state, switch (state) {
            case NOT_STARTED -> "Registre o objetivo e a atividade do projeto.";
            case COMPLETED -> "Os campos da triagem estão preenchidos. A análise técnica segue na preparação.";
            default -> "Ainda há informações a esclarecer na triagem.";
        }, "DRAFT_OWNER", view.version(), new Destination("triage", null, null, null), state != State.COMPLETED);
    }

    private static int intakeVersion(Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT coalesce(max(version), 0) FROM app_project_intake_draft_revision WHERE project_id = ?
                """, row -> row.getInt(1), project);
    }

    private static Task intakeTask(Definition definition, int version, Set<String> actions) {
        return task(definition, version == 0 ? State.NOT_STARTED : State.COMPLETED,
                version == 0 ? "Salve a primeira revisão das informações técnicas."
                        : "Há uma revisão técnica preliminar disponível.",
                "PREP", version, new Destination("technical", "preparar", null, null),
                version == 0 && actions.contains("SAVE_INTAKE"));
    }

    private static Task guideTask(Connection connection, String project, Definition definition, ProjectGuides.Guide guide,
                                  Set<String> visibleGuides) throws SQLException {
        boolean changed = quantificationInputsChanged(connection, project, guide, visibleGuides);
        State state = changed ? State.REQUIRES_RECHECK : guide.version() == 0 ? State.NOT_STARTED
                : guide.missing().isEmpty() ? State.COMPLETED : State.NEEDS_DATA;
        return task(definition, state, switch (state) {
            case REQUIRES_RECHECK -> "Uma entrada do cálculo mudou. Confira e atualize as referências do guia.";
            case NOT_STARTED -> "Abra o guia e registre os dados e as fontes disponíveis.";
            case NEEDS_DATA -> "Há campos a preencher ou fontes a indicar neste guia.";
            default -> "Os campos do guia estão preenchidos. Consulte os requisitos e as evidências na análise.";
        }, "PREP", guide.version(), new Destination("technical",
                    "baseline".equals(guide.id()) ? "linha-base" :
                    "additionality".equals(guide.id()) ? "adicionalidade" : "guias",
                    Set.of("baseline","additionality").contains(guide.id()) ? null : guide.id(),
                    "baseline".equals(guide.id()) ? "premissas" :
                    "additionality".equals(guide.id()) ? "cronologia" : null),
                guide.editable() && state != State.COMPLETED);
    }

    static boolean quantificationInputsChanged(Connection connection, String project, ProjectGuides.Guide guide,
                                                        Set<String> visibleGuides) throws SQLException {
        if (!"quantification".equals(guide.id()) || guide.version() == 0) return false;
        List<String> changed = query(connection, """
                SELECT r.guide_id FROM app_technical_guide_revision r
                LEFT JOIN app_quantification_guide_input pinned ON pinned.project_id = r.project_id
                    AND pinned.input_id = r.guide_id AND pinned.version = ?
                WHERE r.project_id = ? AND r.guide_id IN ('applicability','baseline','additionality','monitoring')
                    AND r.version > coalesce(pinned.input_version, 0)
                GROUP BY r.guide_id
                """, row -> row.getString(1), guide.version(), project);
        return changed.stream().anyMatch(visibleGuides::contains);
    }

    private static Task dossierTask(Connection connection, String project, Definition definition, int intake,
                                    Set<String> actions) throws SQLException {
        var dossier = first(connection, """
                SELECT revision, intake_version FROM app_project_preliminary_dossier
                WHERE project_id = ? ORDER BY revision DESC LIMIT 1
                """, row -> new Dossier(row.getInt(1), row.getInt(2)), project);
        State state = dossier == null ? (intake == 0 ? State.NEEDS_DATA : State.NOT_STARTED)
                : dossier.intake() != intake ? State.REQUIRES_RECHECK : State.COMPLETED;
        return task(definition, state, switch (state) {
            case NEEDS_DATA -> "A prévia precisa de uma revisão das informações técnicas.";
            case NOT_STARTED -> "O rascunho técnico já pode servir de base para a primeira prévia.";
            case REQUIRES_RECHECK -> "A prévia usa uma revisão técnica anterior. Gere outra a partir dos dados atuais.";
            default -> "A prévia corresponde às informações técnicas salvas.";
        }, "PREP", dossier == null ? 0 : dossier.revision(), new Destination("technical", "redigir", null, null),
                actions.contains("PREPARE_DOSSIER") && intake > 0 && state != State.COMPLETED);
    }

    private Task documentTask(Connection connection, DynamicProjectScope.Context context, Definition definition,
                              ProjectGuides.Guide guide, Set<String> visibleGuides) throws SQLException {
        var section = PdSections.latest(connection, context.id(), guide.id());
        if (definition.kind() == Kind.SECTION) {
            boolean stale = section != null && (section.guideVersion() != guide.version()
                    || quantificationInputsChanged(connection, context.id(), guide, visibleGuides));
            State state = stale ? State.REQUIRES_RECHECK : section != null ? State.COMPLETED
                    : guide.version() == 0 ? State.NEEDS_DATA : State.NOT_STARTED;
            boolean locked = section != null && PdReviews.locked(connection, context.id(), guide.id(), section.number());
            boolean editable = context.actions().contains("PREPARE_DOSSIER") && guide.version() > 0
                    && state != State.COMPLETED;
            return task(definition, state, switch (state) {
                case REQUIRES_RECHECK -> locked ? "A origem da seção mudou. Consulte a revisão antes de reabrir o texto."
                        : "A origem da seção mudou. Confira as entradas antes de redigir uma nova versão.";
                case NEEDS_DATA -> "Salve uma revisão do guia para começar a redação.";
                case NOT_STARTED -> "Use o guia salvo para redigir a seção do PD.";
                default -> "Existe uma versão redigida da seção.";
            }, "PREP", section == null ? 0 : section.number(),
                    new Destination("technical", locked && stale ? "revisar" : "redigir", guide.id(), null), editable);
        }

        var destination = new Destination("technical", "revisar", guide.id(), null);
        if (section == null) {
            // The review queue cannot open a section that has yet to be drafted.
            return task(definition, State.NEEDS_DATA, "A revisão começa após a redação da seção.",
                    "PREP", 0, null, false);
        }
        var item = reviews.item(connection, context, guide, section);
        var state = item.history().isEmpty() ? PdReviews.State.DRAFT : item.history().getLast().state();
        if (item.outdated()) {
            boolean reopen = item.actions().contains(PdReviews.State.REOPENED);
            boolean returnForCorrections = item.actions().contains(PdReviews.State.RETURNED);
            return task(definition, State.REQUIRES_RECHECK,
                    "A versão em revisão usa um guia anterior. Confira a atualização antes de seguir.",
                    state == PdReviews.State.SUBMITTED ? "REV" : "PREP", section.number(), destination,
                    reopen || returnForCorrections);
        }
        return task(definition, switch (state) {
            case SUBMITTED -> State.IN_REVIEW;
            case RETURNED -> State.RETURNED;
            case APPROVED_INTERNAL, FROZEN_INTERNAL -> State.COMPLETED;
            case REOPENED -> State.IN_PROGRESS;
            case DRAFT -> State.NOT_STARTED;
        }, switch (state) {
            case SUBMITTED -> "A versão foi encaminhada e aguarda parecer interno.";
            case RETURNED -> "Consulte o motivo da devolução e revise a seção.";
            case APPROVED_INTERNAL -> "O REV aprovou esta versão internamente.";
            case FROZEN_INTERNAL -> "Esta versão foi congelada após a aprovação interna.";
            case REOPENED -> "A versão foi reaberta para edição.";
            case DRAFT -> "A seção está disponível para encaminhamento ao REV.";
        }, switch (state) {
            case SUBMITTED, APPROVED_INTERNAL, FROZEN_INTERNAL -> "REV";
            default -> "PREP";
        }, section.number(), destination, item.actions().stream().anyMatch(action -> action != PdReviews.State.REOPENED));
    }

    private static Task task(Definition definition, State state, String reason, String responsible,
                             int version, Destination destination, boolean actionable) {
        return new Task(definition.id(), definition.kind(), definition.title(), state, reason, responsible,
                version, destination, actionable);
    }
}
