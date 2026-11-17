package br.carbonflow.pilot;

import java.net.URI;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.ListingEvidence.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class ListingWorkflow {
    static final String SIMULATION = "SIMULACAO";
    static final String POWER = "SIM-PODER-LISTAGEM";
    static final String RETRY_PROOF = "SIM-CONFERENCIA-SEM-RECIBO";
    static final String REACTIVATION = "SIM-PROVA-ADMISSAO-REATIVACAO";
    static final String NEW_ATTEMPT = "SIM-PROVA-ADMISSAO-NOVA-TENTATIVA";
    private static final String FIXTURE = "Kit 0.2.14 · E6/exemplos.json · channelCheck e publicationCapture";

    enum Channel {
        ARQUIVO, DIGITAL_HUMANO, INDETERMINADO;

        String destination() { return this == DIGITAL_HUMANO ? "SIM-HUB" : "SIM-REGISTRY"; }
        IntakeModel.Ref evidence() { return new IntakeModel.Ref("SIM-CANAL-" + name().replace('_', '-'), 1); }
    }

    record Create(DocumentRules.Act kind, DocumentRules.Ref dossier, UUID predecessor, IntakeModel.Ref admission) {}
    record CheckInput(DocumentRules.Ref dossier, Channel channel, String documentEdition, String methodologyEdition,
                      OffsetDateTime checkedAt, OffsetDateTime powerUntilExclusive, IntakeModel.Ref source, IntakeModel.Ref power) {}
    record Check(CheckInput input, String responsible, String fixture, UUID eventId) {}
    record Action(String transitionId, String mode, OffsetDateTime effectiveAt, Proof proof, String target,
                  UUID exportId, DocumentRules.Ref dossier, String page, String reason, IntakeModel.Ref support, String captureChannel) {}
    record Fees(Capture invoice, Capture settlement) {}
    record Submission(UUID exportId, String receipt, DocumentRules.Ref dossier, String sha256, String destination) {}
    record Listing(UUID id, int version, DocumentRules.Act kind, DossierPackages.ListingPackage pack, UUID predecessor,
                   String state, String target, Check check, boolean uncertain, Fees fees, Submission submission,
                   Capture publication, UUID consultationId, OffsetDateTime demandAt, OffsetDateTime inactiveAt,
                   OffsetDateTime lastAt, UUID eventId) {
        OffsetDateTime deadline() { return demandAt == null ? null : demandAt.plusDays(90); }
    }
    record View(Listing attempt, OffsetDateTime responseDue, OffsetDateTime rejectionReviewAt, boolean lateResponse,
                String channelStatus, String nextStep, boolean realSubmissionAllowed) {}

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final DossierPackages dossiers;
    private final ListingEvidence evidence;
    private final ConsultationWorkflow consultations;
    private final ObjectMapper mapper;

    ListingWorkflow(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake, DossierPackages dossiers,
                    ListingEvidence evidence, ConsultationWorkflow consultations, ObjectMapper mapper) {
        this.database = database;
        this.commands = commands;
        this.audit = audit;
        this.intake = intake;
        this.dossiers = dossiers;
        this.evidence = evidence;
        this.consultations = consultations;
        this.mapper = mapper;
    }

    static Map<String, Object> options() {
        return Map.of("channels", Arrays.stream(Channel.values()).map(channel -> Map.of("route", channel,
                "source", channel.evidence(), "destination", channel.destination())).toList(),
                "proofs", PROOFS, "documentEdition", DocumentRules.EDITION, "methodologyEdition", "VM0038 v1.1",
                "power", new IntakeModel.Ref(POWER, 1), "fixture", FIXTURE);
    }

    List<View> list(String project, OffsetDateTime asOf) {
        return database.read(connection -> query(connection, """
                SELECT state FROM pilot_listing WHERE project_id=? ORDER BY (state->>'lastAt') DESC NULLS LAST,id LIMIT 100
                """, rows -> {
            var listing = decode(rows.getString(1), Listing.class);
            return view(listing, asOf == null ? listing.lastAt() : local(asOf));
        }, project));
    }

    List<PilotAudit.Event> history(String project, UUID id) {
        return database.transaction(connection -> {
            get(connection, project, id);
            return query(connection, """
                    SELECT id,actor_id,action,outcome,target,occurred_at,details FROM pilot_access_event
                    WHERE project_id=? AND target=? ORDER BY occurred_at DESC,id DESC LIMIT 100
                    """, row -> new PilotAudit.Event(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                    row.getString(4), row.getString(5), row.getObject(6, OffsetDateTime.class), audit.decode(row.getString(7))),
                    project, id.toString());
        });
    }

    JsonNode create(PilotAccess.Scope scope, String actor, String key, Create input) {
        if (input == null || (input.kind() != DocumentRules.Act.DESENVOLVIMENTO && input.kind() != DocumentRules.Act.LISTAGEM_VALIDACAO)
                || !valid(input.dossier())) throw invalid("LISTAGEM_INVALIDA", "Selecione o tipo de listagem e seu dossiê versionado.");
        return commands.execute(scope, actor, "PREPARAR_LISTAGEM", key, audit.encode(input), null, connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var pack = dossiers.listingPackage(connection, project, input.dossier(), input.kind(), true);
            var latest = first(connection, """
                    SELECT state FROM pilot_listing WHERE project_id=? AND kind=? AND NOT EXISTS (
                        SELECT 1 FROM pilot_listing n WHERE n.project_id=pilot_listing.project_id AND n.predecessor=pilot_listing.id)
                    """, row -> decode(row.getString(1), Listing.class), project, input.kind().name());
            if (latest != null) {
                require("REJEITADO".equals(latest.state()) && latest.id().equals(input.predecessor())
                        && new IntakeModel.Ref(NEW_ATTEMPT, 1).equals(input.admission())
                        && !latest.pack().reference().equals(pack.reference())
                        && !pack.actDate().isBefore(latest.lastAt().toLocalDate()),
                        "TENTATIVA_EXISTENTE", "Use a tentativa em curso. Após rejeição, informe admissão e pacote atualizado para a sucessora.");
            } else {
                require(input.predecessor() == null, "ANTECESSOR_DIVERGENTE", "Confira a tentativa anterior para este ato.");
            }
            UUID id = UUID.randomUUID();
            UUID event = audit.record(connection, project, actor, "PREPARAR_LISTAGEM", "PERMITIDO", id.toString(), input);
            var created = new Listing(id, 1, input.kind(), pack, input.predecessor(), "NOVO", null, null, false,
                    null, null, null, null, null, null, null, event);
            update(connection, "INSERT INTO pilot_listing VALUES (?,?,?,?,?,?::jsonb)", project, id, input.kind().name(),
                    input.predecessor(), created.version(), audit.encode(created));
            return view(created, null);
        });
    }

    JsonNode check(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, CheckInput input) {
        if (input == null || !valid(input.dossier()) || input.channel() == null || !DocumentRules.EDITION.equals(input.documentEdition())
                || !"VM0038 v1.1".equals(input.methodologyEdition()) || !input.channel().evidence().equals(input.source())
                || input.checkedAt() == null
                || (input.power() != null && (!new IntakeModel.Ref(POWER, 1).equals(input.power()) || input.powerUntilExclusive() == null))
                || (input.power() == null && input.powerUntilExclusive() != null)) {
            throw invalid("CONFERENCIA_INCOMPLETA", "Informe edição, data, canal e prova de conferência. Vincule a vigência se houver prova de poder.");
        }
        return commands.execute(scope, actor, "CONFERIR_CANAL_LISTAGEM", key, audit.encode(List.of(id, expected, input)), id.toString(), connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var listing = get(connection, project, id);
            PilotCommands.requireVersion(expected, listing.version());
            require(List.of("NOVO", "PENDENTE", "INATIVO").contains(listing.state()) && !listing.uncertain(),
                    "CONFERENCIA_INDISPONIVEL", "Confira o retorno pendente antes de alterar o canal.");
            var packageView = dossiers.listingPackage(connection, project, input.dossier(), listing.kind(), true);
            boolean same = packageView.reference().equals(listing.pack().reference());
            require(same || successor(listing, packageView), "PACOTE_INCOMPATIVEL",
                    "Use o pacote da tentativa ou uma revisão sucessora da mesma finalidade.");
            UUID event = audit.record(connection, project, actor, "CONFERIR_CANAL_LISTAGEM", "PERMITIDO", id.toString(), input);
            var saved = new Check(input, actor, FIXTURE, event);
            var next = new Listing(id, listing.version() + 1, listing.kind(),
                    "NOVO".equals(listing.state()) ? packageView : listing.pack(), listing.predecessor(), listing.state(),
                    listing.target(), saved, false, listing.fees(), listing.submission(), listing.publication(), listing.consultationId(),
                    listing.demandAt(), listing.inactiveAt(), listing.lastAt(), event);
            return view(save(connection, project, next), local(input.checkedAt()));
        });
    }

    View readiness(String project, UUID id, OffsetDateTime effectiveAt) {
        return database.read(connection -> view(get(connection, project, id), local(effectiveAt)));
    }

    JsonNode act(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, Action command) {
        if (command == null || command.transitionId() == null || !ALLOWED.contains(command.transitionId())) {
            throw invalid("ACAO_INVALIDA", "Selecione uma ação da tentativa.");
        }
        if (!SIMULATION.equals(command.mode())) {
            throw invalid("USO_REAL_BLOQUEADO", "Esta entrega registra somente atos e comprovantes de simulação.");
        }
        if (command.captureChannel() != null && !List.of("CAPTURA_MANUAL", "CAPTURA_ARQUIVO").contains(command.captureChannel())) {
            throw invalid("CAPTURA_INVALIDA", "Escolha o meio de captura simulado.");
        }
        var at = local(command.effectiveAt());
        return commands.execute(scope, actor, command.transitionId(), key, audit.encode(List.of(id, expected, command)), id.toString(),
                connection -> apply(connection, scope.project(), actor, id, expected, command, at));
    }

    private Object apply(Connection connection, String project, String actor, UUID id, int expected, Action command, OffsetDateTime at)
            throws SQLException {
        intake.lockProject(connection, project);
        var old = get(connection, project, id);
        var step = command.transitionId();
        boolean official = PROOFS.containsKey(step);
        String authority = "QUITACAO".equals(step) ? PAYMENT : VERRA;
        String hash = null;
        if (official) {
            verify(step, command.proof());
            hash = evidence.digest(Arrays.asList(step, command.proof().target(), at.toInstant(), command.page(), command.reason(),
                    "QUITACAO".equals(step) ? command.target() : null));
            var replay = evidence.repeated(connection, project, id, authority, VERRA_LISTING, command.proof(), hash);
            if (replay != null) return replay;
            require(Objects.equals(old.target(), command.proof().target()), "ATO_DE_OUTRO_PEDIDO", "O ato pertence a outro alvo no canal.");
        }

        boolean protocol = List.of("TX-E3-09", "TX-E3-18", "TX-E3-11", "TX-E3-13").contains(step);
        if (protocol && command.exportId() != null) {
            var replay = first(connection, """
                    SELECT listing_id,response FROM pilot_listing_receipt WHERE project_id=? AND export_id=?
                    """, row -> {
                require(id.equals(row.getObject(1, UUID.class)), "RECIBO_REUTILIZADO", "O recibo já pertence a outra tentativa.");
                var result = audit.decode(row.getString(2));
                require(step.equals(result.path("step").asText()) && Objects.equals(command.target(), result.path("attempt").path("target").asText())
                        && command.dossier() != null && command.dossier().equals(decode(result.at("/attempt/pack/reference").toString(), DocumentRules.Ref.class))
                        && at.isEqual(OffsetDateTime.parse(result.at("/attempt/lastAt").asText())),
                        "RECIBO_DIVERGENTE", "O recibo já tem versão, data e alvo fixados.");
                return result;
            }, project, command.exportId());
            if (replay != null) return replay;
        }
        PilotCommands.requireVersion(expected, old.version());
        require(old.lastAt() == null || !at.isBefore(old.lastAt()), "DATA_RETROATIVA",
                "A data efetiva precisa respeitar os marcos já aplicados à tentativa.");
        if (official) {
            require(command.page() == null || "TX-E3-14".equals(step), "PAGINA_INCOMPATIVEL", "Vincule a página na publicação.");
        }

        String state = old.state();
        String target = old.target();
        var pack = old.pack();
        var submission = old.submission();
        var publication = old.publication();
        var consultation = old.consultationId();
        var fees = old.fees();
        var demandAt = old.demandAt();
        var inactiveAt = old.inactiveAt();
        boolean uncertain = old.uncertain();

        if (protocol) {
            validateProtocol(connection, project, old, command, at);
            var delivery = dossiers.listingDelivery(connection, project, command.dossier(), command.exportId(), old.kind(),
                    old.check().input().channel().destination(), !old.uncertain());
            require(delivery.receipt().input().receivedAt().isEqual(at), "RECIBO_DIVERGENTE", "Use a data efetiva do recibo.");
            if ("NOVO".equals(old.state())) bindTarget(connection, project, id, command.target());
            target = command.target();
            pack = delivery.pack();
            submission = new Submission(command.exportId(), delivery.receipt().input().code(), pack.reference(),
                    delivery.export().sha256(), delivery.export().destination());
            state = "PROTOCOLADO";
            uncertain = false;
            if ("TX-E3-11".equals(step)) demandAt = null;
        } else if ("ENVIO_INCERTO".equals(step) || "CONFIRMAR_AUSENCIA".equals(step)) {
            require(List.of("NOVO", "PENDENTE", "INATIVO").contains(old.state()) && IntakeRules.text(command.reason()),
                    "RETORNO_INDISPONIVEL", "Informe a situação do retorno na etapa de envio.");
            if ("ENVIO_INCERTO".equals(step)) {
                require(!old.uncertain(), "RETORNO_PENDENTE", "Confira o canal antes de repetir o envio.");
                channelReady(old, old.check() == null ? old.pack().reference() : old.check().input().dossier(), at);
                uncertain = true;
            } else {
                require(old.uncertain() && new IntakeModel.Ref(RETRY_PROOF, 1).equals(command.support()),
                        "CONFIRMACAO_AUSENTE", "Vincule a prova da conferência manual sem recibo.");
                uncertain = false;
            }
        } else {
            switch (step) {
                case "BOLETO" -> require(List.of("PROTOCOLADO", "INATIVO").contains(state) && (fees == null
                                || "INATIVO".equals(state) && fees.settlement() != null
                                && fees.invoice().effectiveAt().isBefore(inactiveAt)), "COBRANCA_INDISPONIVEL",
                        "A cobrança exige tentativa protocolada ou reativação. Confira o boleto já registrado.");
                case "QUITACAO" -> require(fees != null && fees.settlement() == null && fees.invoice().proof().officialId().equals(command.target()),
                        "QUITACAO_SEM_BOLETO", "Vincule a quitação ao identificador do boleto em aberto.");
                case "TX-E3-10" -> { predecessor(old, "PROTOCOLADO"); reason(command); state = "PENDENTE"; demandAt = at; }
                case "TX-E3-12" -> { predecessor(old, "PENDENTE"); reason(command); state = "INATIVO"; inactiveAt = at; }
                case "TX-E3-14" -> {
                    predecessor(old, "PROTOCOLADO");
                    require(page(command.page(), target), "PUBLICACAO_SEM_PAGINA", "Identifique a página da publicação no Registry de ensaio.");
                    require(paid(connection, project, old), "TAXA_PENDENTE", "Junte a quitação aplicável a esta listagem.");
                    state = "PUBLICADO";
                }
                case "TX-E3-15" -> {
                    require(List.of("PROTOCOLADO", "PENDENTE").contains(state), "TRANSICAO_INDISPONIVEL", "Confira o estado da tentativa.");
                    reason(command); state = "SUSPENSO";
                }
                case "TX-E3-16" -> { predecessor(old, "SUSPENSO"); reason(command); state = "PROTOCOLADO"; demandAt = null; }
                case "TX-E3-17" -> {
                    require(List.of("PROTOCOLADO", "PENDENTE", "INATIVO", "SUSPENSO").contains(state),
                            "TRANSICAO_INDISPONIVEL", "Confira o estado da tentativa.");
                    reason(command); state = "REJEITADO"; uncertain = false;
                }
                default -> throw invalid("ACAO_INVALIDA", "Selecione uma ação da tentativa.");
            }
        }
        var details = new java.util.LinkedHashMap<String, Object>();
        details.put("from", old.state()); details.put("to", state); details.put("effectiveAt", at);
        details.put("command", command); details.put("package", pack.reference()); details.put("sha256", pack.sha256());
        if (official) details.put("authority", authority);
        UUID event = audit.record(connection, project, actor, step, "PERMITIDO", id.toString(), details);
        Capture capture = official ? new Capture(authority, VERRA_LISTING, command.proof(), at, actor, event) : null;
        if ("BOLETO".equals(step)) fees = new Fees(capture, null);
        if ("QUITACAO".equals(step)) fees = new Fees(fees.invoice(), capture);
        if ("TX-E3-14".equals(step)) {
            publication = capture;
            if (old.kind() == DocumentRules.Act.LISTAGEM_VALIDACAO) {
                consultation = consultations.open(connection, project, id, target, command.page(), capture);
            }
        }
        boolean localReturn = "ENVIO_INCERTO".equals(step) || "CONFIRMAR_AUSENCIA".equals(step);
        var next = new Listing(id, old.version() + 1, old.kind(), pack, old.predecessor(), state, target, old.check(), uncertain, fees,
                submission, publication, consultation, demandAt, inactiveAt, localReturn ? old.lastAt() : at, event);
        save(connection, project, next);
        var current = audit.decode(audit.encode(view(next, at)));
        if (official) evidence.save(connection, project, id, capture, hash, current);
        if (protocol) {
            ((com.fasterxml.jackson.databind.node.ObjectNode) current).put("step", step);
            update(connection, "INSERT INTO pilot_listing_receipt VALUES (?,?,?,?::jsonb)", project, command.exportId(), id, audit.encode(current));
        }
        return current;
    }

    private void validateProtocol(Connection connection, String project, Listing old, Action command, OffsetDateTime at) throws SQLException {
        var step = command.transitionId();
        if (!valid(command.dossier()) || command.exportId() == null || !IntakeRules.id(command.target())) {
            throw invalid("PEDIDO_INCOMPLETO", "Informe a revisão do dossiê, o recibo da exportação e o alvo externo.");
        }
        var proposed = dossiers.listingPackage(connection, project, command.dossier(), old.kind(), !old.uncertain());
        channelReady(old, proposed.reference(), at);
        if ("TX-E3-09".equals(step) || "TX-E3-18".equals(step)) {
            predecessor(old, "NOVO");
            boolean development = "TX-E3-09".equals(step);
            require(development == (old.kind() == DocumentRules.Act.DESENVOLVIMENTO)
                    && proposed.reference().equals(old.pack().reference()), "PACOTE_INCOMPATIVEL", "Confira a finalidade da tentativa.");
            if (!development) require(developmentPublished(connection, project, at), "DESENVOLVIMENTO_PENDENTE",
                    "Registre primeiro a publicação efetiva em desenvolvimento.");
            return;
        }
        require(command.target().equals(old.target()) && successor(old, proposed), "PACOTE_INCOMPATIVEL",
                "Use a mesma tentativa e confira a revisão sucessora da mesma finalidade.");
        reason(command);
        if ("TX-E3-11".equals(step)) {
            predecessor(old, "PENDENTE");
            require(!at.isAfter(old.deadline()), "PRAZO_DE_RESPOSTA_VENCIDO", "Confira a decisão da Verra antes de reencaminhar.");
        } else {
            predecessor(old, "INATIVO");
            require(new IntakeModel.Ref(REACTIVATION, 1).equals(command.support()) && old.fees() != null
                            && old.fees().settlement() != null && !old.fees().invoice().effectiveAt().isBefore(old.inactiveAt()),
                    "REATIVACAO_SEM_PROVA", "Junte a admissão da reativação e a quitação da nova cobrança.");
        }
    }

    private boolean developmentPublished(Connection connection, String project, OffsetDateTime at) throws SQLException {
        return first(connection, """
                SELECT true FROM pilot_listing WHERE project_id=? AND kind='DESENVOLVIMENTO' AND state->>'state'='PUBLICADO'
                AND (state->'publication'->>'effectiveAt')::timestamptz<=? LIMIT 1
                """, row -> true, project, at) != null;
    }

    private boolean paid(Connection connection, String project, Listing listing) throws SQLException {
        if (listing.fees() != null) return listing.fees().settlement() != null;
        return listing.kind() == DocumentRules.Act.LISTAGEM_VALIDACAO && first(connection, """
                SELECT true FROM pilot_listing WHERE project_id=? AND kind='DESENVOLVIMENTO' AND state->>'state'='PUBLICADO'
                AND state->'fees'->>'settlement' IS NOT NULL LIMIT 1
                """, row -> true, project) != null;
    }

    private static boolean successor(Listing old, DossierPackages.ListingPackage fresh) {
        return fresh.reference().id().equals(old.pack().reference().id()) && fresh.reference().revision() > old.pack().reference().revision();
    }

    private static void bindTarget(Connection connection, String project, UUID id, String target) throws SQLException {
        int inserted = update(connection, "INSERT INTO pilot_listing_target VALUES (?,?,?,?) ON CONFLICT DO NOTHING",
                VERRA_LISTING, target, project, id);
        require(inserted == 1, "ALVO_JA_VINCULADO", "Use o identificador externo específico desta tentativa.");
    }

    private static void channelReady(Listing listing, DocumentRules.Ref dossier, OffsetDateTime at) {
        var check = listing.check() == null ? null : listing.check().input();
        require(check != null && check.channel() != Channel.INDETERMINADO && check.dossier().equals(dossier)
                        && !at.isBefore(check.checkedAt()), "CANAL_INDETERMINADO", "Confirme o canal e a edição para este ato e pacote.");
        require(check.power() != null && at.isBefore(check.powerUntilExclusive()), "PODER_INDISPONIVEL",
                "Vincule a prova do poder vigente na data do ato simulado.");
    }

    private static void predecessor(Listing old, String state) {
        require(state.equals(old.state()), "TRANSICAO_INDISPONIVEL", "A etapa exige a tentativa em " + state + ".");
    }

    private static void reason(Action command) {
        if (!IntakeRules.text(command.reason())) throw invalid("MOTIVO_AUSENTE", "Informe motivo e providência.");
    }

    private static boolean page(String value, String target) {
        if (value == null || value.length() > 200) return false;
        try {
            var page = URI.create(value);
            return "https".equals(page.getScheme()) && "registry.example.invalid".equals(page.getAuthority())
                    && ("/listagens/" + target).equals(page.getRawPath()) && page.getQuery() == null && page.getFragment() == null;
        } catch (IllegalArgumentException error) { return false; }
    }

    private Listing get(Connection connection, String project, UUID id) throws SQLException {
        var item = first(connection, "SELECT state FROM pilot_listing WHERE project_id=? AND id=?",
                row -> decode(row.getString(1), Listing.class), project, id);
        if (item == null) throw PilotAccess.invisible();
        return item;
    }

    private Listing save(Connection connection, String project, Listing value) throws SQLException {
        update(connection, "UPDATE pilot_listing SET state=?::jsonb, version=? WHERE project_id=? AND id=?",
                audit.encode(value), value.version(), project, value.id());
        return value;
    }

    private static View view(Listing listing, OffsetDateTime asOf) {
        var deadline = listing.deadline();
        boolean overdue = "PENDENTE".equals(listing.state()) && deadline != null && asOf != null && asOf.isAfter(deadline);
        var check = listing.check() == null ? null : listing.check().input();
        String channelStatus;
        if (check == null || check.channel() == Channel.INDETERMINADO || asOf != null && asOf.isBefore(check.checkedAt())) {
            channelStatus = "CANAL_INDETERMINADO";
        } else if (check.power() == null || !(asOf == null ? check.checkedAt() : asOf).isBefore(check.powerUntilExclusive())) {
            channelStatus = "PODER_INDISPONIVEL";
        } else {
            channelStatus = "CONFERIDO_SIMULACAO";
        }
        String nextStep;
        if (listing.uncertain()) nextStep = "Confira no canal se existe recibo antes de repetir.";
        else if (overdue) nextStep = "Prazo nominal vencido. Confira a decisão antes de alterar a tentativa.";
        else if ("NOVO".equals(listing.state())) nextStep = "Confira canal, edição e poder. Vincule o recibo da revisão congelada.";
        else nextStep = "Registre o próximo ato comprovado na trilha da tentativa.";
        return new View(listing, deadline, listing.inactiveAt() == null ? null : listing.inactiveAt().plusMonths(12),
                overdue, channelStatus, nextStep, false);
    }

    private <T> T decode(String json, Class<T> type) {
        try { return mapper.readValue(json, type); }
        catch (java.io.IOException error) { throw new IllegalStateException("Falha ao ler tentativa", error); }
    }

    private static boolean valid(DocumentRules.Ref ref) { return ref != null && ref.id() != null && ref.revision() > 0; }
    private static final List<String> ALLOWED = List.of("TX-E3-09", "TX-E3-10", "TX-E3-11", "TX-E3-12", "TX-E3-13",
            "TX-E3-14", "TX-E3-15", "TX-E3-16", "TX-E3-17", "TX-E3-18", "BOLETO", "QUITACAO", "ENVIO_INCERTO", "CONFIRMAR_AUSENCIA");
}
