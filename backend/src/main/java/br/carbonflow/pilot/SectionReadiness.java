package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;

@ApplicationScoped
class SectionReadiness {
    enum GateStatus { RESOLVED_FOR_EXAMPLE, NEEDS_DATA, DRAFT_DATA_RECORDED, NOT_EVALUATED }
    record Gate(String code, String title, GateStatus status) {}
    record Window(LocalDate from, LocalDate until) {}
    record InputNeed(String field, String label, String reason, boolean editable, String screen) {}
    record Denominator(int applicableMandatoryOrConditional, int recorded, int indeterminate) {}
    record View(String section, int guideRevision, RequirementResolver.Act act, RequirementResolver.State catalogState,
                String catalogReason, String packId, Integer packEdition, RequirementResolver.DateBasis dateBasis,
                LocalDate dateUsed, Window window, List<Gate> gates, Denominator counts, List<RequirementGraph.Node> requirements,
                List<InputNeed> acquisitionPlan) {}

    private final DynamicProjectScope scopes;
    private final ProjectGuides guides;
    private final RequirementResolver resolver;

    SectionReadiness(DynamicProjectScope scopes, ProjectGuides guides, RequirementResolver resolver) {
        this.scopes = scopes;
        this.guides = guides;
        this.resolver = resolver;
    }

    View get(String cookie, String project, String section, RequirementResolver.Act act) {
        return scopes.read(cookie,project,"VIEW_GUIDES",(connection,scope) -> view(connection,scope,section,act));
    }

    View view(Connection connection, DynamicProjectScope.Context scope, String section,
                      RequirementResolver.Act act) throws SQLException {
        Map<String, ProjectGuides.Guide> visible = guides.catalog(connection,scope).guides().stream()
                .collect(Collectors.toMap(ProjectGuides.Guide::id,Function.identity()));
        var guide = visible.get(section);
        if (guide == null) {
            throw new PilotProblem(404,"RECURSO_NAO_VISIVEL","section","ECA-003","Seção indisponível para esta conta.");
        }

        boolean viewIntake = scope.actions().contains("VIEW_INTAKE");
        LocalDate actDate = act == RequirementResolver.Act.PD && viewIntake
                ? DynamicProjectTechnical.referenceActDate(connection,scope.id()) : null;
        var quantification = visible.get("quantification");
        String period = act == RequirementResolver.Act.MR && quantification != null
                ? quantification.answers().get("periodStart") : null;
        var input = new RequirementResolver.Input(act,
                actDate,date(period),null,null);
        var regime = resolver.resolve(connection,scope,input);

        var rules = regime.rules().stream().filter(result -> visible.containsKey(result.rule().section())).toList();
        Set<String> recorded = new HashSet<>();
        for (var entry : rules) {
            var rule = entry.rule();
            if ("referenceActDate".equals(rule.field()) ? input.actDate() != null
                    : visible.get(rule.section()).answers().containsKey(rule.field())) {
                recorded.add(rule.code());
            }
        }

        Set<String> stale = new HashSet<>();
        Window window = null;
        Set<String> optional = Set.of();
        if (regime.state() == RequirementResolver.State.RESOLVED_SYNTHETIC) {
            optional = Set.copyOf(query(connection, "SELECT code FROM app_norm_rule WHERE pack_id=? AND optional",
                    row -> row.getString(1),regime.packId()));
            window = first(connection, """
                    SELECT effective_from,effective_until FROM app_norm_pack_window WHERE pack_id=? AND act=?
                    """, row -> new Window(row.getObject(1,LocalDate.class),row.getObject(2,LocalDate.class)),
                    regime.packId(),act.name());
            if (quantification != null && ProjectWork.quantificationInputsChanged(connection,scope.id(),quantification,
                    visible.keySet())) stale.add("quantification");
            if (scope.actions().contains("VIEW_DOSSIER") && act == RequirementResolver.Act.PD) {
                for (var current : visible.values()) {
                    var document = PdSections.latest(connection,scope.id(),current.id());
                    if (document != null && document.guideVersion() != current.version()) stale.add(current.id());
                }
            }
        }

        // An inaccessible predecessor still blocks evaluation. Its identifier and state are removed from the projection.
        var nodes = evaluateScoped(rules, new RequirementGraph.Inputs(recorded,stale,optional));
        var sectionNodes = nodes.stream().filter(node -> node.guide().equals(section)).toList();
        var plan = plan(guide,regime,sectionNodes,viewIntake,scope.actions().contains("SAVE_INTAKE"));
        return new View(section,guide.version(),act,regime.state(),regime.reason(),regime.packId(),regime.packEdition(),
                regime.dateBasis(),regime.dateUsed(),window,gates(sectionNodes,regime.state(),plan),
                regime.state() == RequirementResolver.State.RESOLVED_SYNTHETIC && !sectionNodes.isEmpty()
                        ? count(sectionNodes) : null,sectionNodes,plan);
    }

    private static List<RequirementGraph.Node> evaluateScoped(List<RequirementResolver.RuleResult> visible,
                                                               RequirementGraph.Inputs inputs) {
        var visibleIds = visible.stream().map(result -> result.rule().code()).collect(Collectors.toSet());
        // Placeholders contain no observations. They turn a private prerequisite into a generic pending dependency.
        var placeholders = new HashSet<String>();
        var graph = new ArrayList<>(visible);
        for (var node : visible) {
            for (String parent : node.rule().dependencies()) {
                if (visibleIds.contains(parent) || !placeholders.add(parent)) continue;
                var rule = new RequirementResolver.Rule(parent,"","","",null,"",
                        new RequirementResolver.Condition(RequirementResolver.ConditionKind.ALWAYS,null,null,null,null),
                        List.of());
                graph.add(new RequirementResolver.RuleResult(rule,RequirementResolver.Incidence.INDETERMINATE,"",null));
            }
        }
        return RequirementGraph.evaluate(graph,inputs).stream().filter(node -> visibleIds.contains(node.code()))
                .map(node -> new RequirementGraph.Node(node.code(),node.title(),node.guide(),node.field(),
                        node.classification(),node.status(),node.reason(),
                        node.dependencies().stream().filter(visibleIds::contains).toList(),
                        node.source(),node.locator(),node.interpretation())).toList();
    }

    private static List<InputNeed> plan(ProjectGuides.Guide guide, RequirementResolver.Resolution regime,
                                        List<RequirementGraph.Node> nodes, boolean viewIntake, boolean editIntake) {
        var needs = new ArrayList<InputNeed>();
        if ("applicability".equals(guide.id()) && regime.act() == RequirementResolver.Act.PD
                && regime.dateUsed() == null && viewIntake) {
            needs.add(new InputNeed("referenceActDate","Data de referência do ato",
                    "A data seleciona a janela do catálogo de exemplo.",editIntake,"preparar"));
        }
        for (var field : guide.fields()) {
            if (!guide.missing().contains(field.label())) continue;
            needs.add(new InputNeed(field.key(),field.label(),"Campo pendente no guia da etapa.",guide.editable(),"guias"));
        }
        for (var node : nodes) {
            if (node.status() == RequirementGraph.Status.COLLECT_DATA
                    && node.classification() == RequirementGraph.Classification.INDETERMINATE) {
                needs.add(new InputNeed(node.code(),node.title(),
                        "A condição ainda exige dado estruturado e comprovável.",false,null));
            }
        }
        return List.copyOf(needs);
    }

    private static List<Gate> gates(List<RequirementGraph.Node> nodes, RequirementResolver.State state, List<InputNeed> plan) {
        GateStatus data = nodes.isEmpty() && plan.isEmpty() ? GateStatus.NOT_EVALUATED
                : !plan.isEmpty() || nodes.stream().anyMatch(node -> node.status() != RequirementGraph.Status.DATA_RECORDED
                        && node.status() != RequirementGraph.Status.NOT_APPLICABLE)
                ? GateStatus.NEEDS_DATA : GateStatus.DRAFT_DATA_RECORDED;
        return List.of(new Gate("G0","Enquadramento do exemplo",state == RequirementResolver.State.RESOLVED_SYNTHETIC
                        ? GateStatus.RESOLVED_FOR_EXAMPLE : GateStatus.NEEDS_DATA),
                new Gate("G1","Coleta e preparação", data),
                new Gate("G2","Evidências e condições",GateStatus.NOT_EVALUATED),
                new Gate("G3","Revisão interna",GateStatus.NOT_EVALUATED),
                new Gate("G4","Validação ou verificação",GateStatus.NOT_EVALUATED),
                new Gate("G5","Ato externo",GateStatus.NOT_EVALUATED));
    }

    private static Denominator count(List<RequirementGraph.Node> nodes) {
        var applicable = nodes.stream().filter(node -> node.classification() == RequirementGraph.Classification.MANDATORY
                || node.classification() == RequirementGraph.Classification.CONDITIONAL).toList();
        return new Denominator(applicable.size(),(int) applicable.stream()
                .filter(node -> node.status() == RequirementGraph.Status.DATA_RECORDED).count(),
                (int) nodes.stream().filter(node -> node.classification() == RequirementGraph.Classification.INDETERMINATE).count());
    }

    private static LocalDate date(String value) {
        return value == null ? null : LocalDate.parse(value);
    }

}
