package br.carbonflow.pilot;

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
class ConsultationWorkflow {
    static final IntakeModel.Ref RESPONSE_PROOF = new IntakeModel.Ref("SIM-PROVA-RESPOSTA-CONSULTA", 1);
    static final IntakeModel.Ref ASSIGNMENT_PROOF = new IntakeModel.Ref("SIM-PROVA-VINCULO-VVB", 1);
    enum Topic { ACESSO, SEGURANCA, IMPACTO_LOCAL, OUTRO }
    enum Result { SUFICIENTE, ACHADO }

    record Consultation(UUID id, int version, UUID listing, String target, String page, String state, OffsetDateTime opening,
                        OffsetDateTime endExclusive, Capture publication, Capture closure, boolean noInWindowComments) {}
    record PublicView(UUID id, String environment, String state, OffsetDateTime publishedAt, OffsetDateTime endExclusive,
                      OffsetDateTime closedAt, long inWindow, long afterWindow, long openIssues) {}
    record Close(OffsetDateTime effectiveAt, Proof proof, Boolean noInWindowComments, IntakeModel.Ref summary) {}
    record Submission(OffsetDateTime effectiveAt, Proof proof, Topic topic, String body, String contact) {}
    record CommentAction(String transitionId, OffsetDateTime effectiveAt, Proof proof, String vvb, String body,
                         Result result, IntakeModel.Ref support) {}
    record Response(int version, String body, OffsetDateTime effectiveAt, String actor, boolean late, IntakeModel.Ref source) {}
    record Evaluation(Result result, String reason, int responseVersion, Capture capture) {}
    record Comment(UUID id, int version, UUID consultation, String target, String state, Topic topic, String body,
                   String window, OffsetDateTime responseDue, Capture received, Capture forwarded, String vvb,
                   List<Response> responses, List<Evaluation> evaluations, UUID eventId) {}
    record VisibleComment(Comment comment, String contact) {}

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;
    private final ListingEvidence evidence;
    private final ObjectMapper mapper;

    ConsultationWorkflow(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake,
                         ListingEvidence evidence, ObjectMapper mapper) {
        this.database = database;
        this.commands = commands;
        this.audit = audit;
        this.intake = intake;
        this.evidence = evidence;
        this.mapper = mapper;
    }

    UUID open(Connection connection, String project, UUID listing, String target, String page, Capture publication)
            throws SQLException {
        UUID id = UUID.randomUUID();
        UUID event = audit.record(connection, project, publication.capturedBy(), "TX-E3-19", "PERMITIDO", id.toString(),
                Map.of("publicationEvent", publication.eventId(), "issuer", VERRA, "opening", publication.effectiveAt()));
        var state = new Consultation(id, 1, listing, target, page, "ABERTA", publication.effectiveAt(),
                publication.effectiveAt().plusDays(30), publication, null, false);
        update(connection, "INSERT INTO pilot_consultation VALUES (?,?,?,?,?::jsonb)",
                project, id, listing, 1, audit.encode(state));
        return id;
    }

    List<Consultation> assigned(String project, String actor) {
        return database.read(connection -> query(connection, """
                SELECT c.state FROM pilot_consultation c WHERE c.project_id=? AND EXISTS (
                    SELECT 1 FROM pilot_consultation_comment m WHERE m.project_id=c.project_id AND m.consultation_id=c.id
                      AND m.state->>'vvb'=?)
                ORDER BY (c.state->>'opening')::timestamptz DESC LIMIT 50
                """, row -> decode(row.getString(1), Consultation.class), project, actor));
    }

    Consultation get(String project, UUID id) {
        return database.read(connection -> get(connection, project, id));
    }

    PublicView publicView(String project, UUID id) {
        return database.transaction(connection -> {
            var consultation = get(connection, project, id);
            return first(connection, """
                    SELECT count(*) FILTER (WHERE state->>'window'='NA_JANELA'),
                           count(*) FILTER (WHERE state->>'window'='FORA_DA_JANELA'),
                           count(*) FILTER (WHERE state->>'state'<>'AVALIADO')
                    FROM pilot_consultation_comment WHERE project_id=? AND consultation_id=?
                    """, row -> new PublicView(id, "SIMULACAO", consultation.state(), consultation.opening(), consultation.endExclusive(),
                    consultation.closure() == null ? null : consultation.closure().effectiveAt(), row.getLong(1), row.getLong(2),
                    row.getLong(3)), project, id);
        });
    }

    JsonNode close(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, Close input) {
        if (input == null || input.noInWindowComments() == null || !new IntakeModel.Ref("SIM-RESUMO-CONSULTA", 1).equals(input.summary())) {
            throw invalid("RESUMO_AUSENTE", "Vincule o resumo sintético e informe se houve manifestações na janela.");
        }
        verify("TX-E3-24", input.proof());
        var at = local(input.effectiveAt());
        return commands.execute(scope, actor, "TX-E3-24", key, audit.encode(List.of(id, expected, input)), id.toString(), connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var current = get(connection, project, id);
            String fingerprint = evidence.digest(List.of("TX-E3-24", input.proof().target(), at.toInstant(), input.noInWindowComments(), input.summary()));
            var prior = evidence.repeated(connection, project, id, VERRA, VERRA_CONSULTATION, input.proof(), fingerprint);
            if (prior != null) return prior;
            PilotCommands.requireVersion(expected, current.version());
            require(current.target().equals(input.proof().target()) && "ABERTA".equals(current.state())
                    && !at.isBefore(current.endExclusive()), "ENCERRAMENTO_INDISPONIVEL",
                    "O encerramento exige prova do alvo certo e trinta dias completos.");
            boolean hasComments = first(connection, """
                    SELECT 1 FROM pilot_consultation_comment WHERE project_id=? AND consultation_id=? AND state->>'window'='NA_JANELA' LIMIT 1
                    """, row -> true, project, id) != null;
            require(hasComments != input.noInWindowComments(), "RESUMO_DIVERGENTE", "O resumo deve refletir as manifestações da janela.");
            UUID event = audit.record(connection, project, actor, "TX-E3-24", "PERMITIDO", id.toString(),
                    Map.of("proof", input.proof(), "summary", input.summary(), "effectiveAt", at, "issuer", VERRA));
            var capture = new Capture(VERRA, VERRA_CONSULTATION, input.proof(), at, actor, event);
            var closed = new Consultation(id, current.version() + 1, current.listing(), current.target(), current.page(), "ENCERRADA",
                    current.opening(), current.endExclusive(), current.publication(), capture, input.noInWindowComments());
            update(connection, "UPDATE pilot_consultation SET version=?,state=?::jsonb WHERE project_id=? AND id=?",
                    closed.version(), audit.encode(closed), project, id);
            var response = audit.decode(audit.encode(closed));
            evidence.save(connection, project, id, capture, fingerprint, response);
            return response;
        });
    }

    JsonNode submit(PilotAccess.Scope scope, String actor, String key, UUID id, Submission input) {
        if (input == null || input.topic() == null || !IntakeRules.text(input.body()) || !IntakeRules.text(input.contact())) {
            throw invalid("MANIFESTACAO_INCOMPLETA", "Informe assunto, conteúdo e contato reservado.");
        }
        verify("TX-E3-20", input.proof());
        var at = local(input.effectiveAt());
        String digest = evidence.digest(List.of("TX-E3-20", at.toInstant(), input.proof().target(), input.topic(), input.body(), input.contact()));
        return commands.execute(scope, actor, "TX-E3-20", key, digest + ":" + id + ":" + input.proof().officialId(), id.toString(), connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var consultation = get(connection, project, id);
            var replay = evidence.repeated(connection, project, id, "PUB", HUB, input.proof(), digest);
            if (replay != null) return replay;
            require(input.proof().target().equals(consultation.target()) && !at.isBefore(consultation.opening()),
                    "MANIFESTACAO_FORA_DO_ESCOPO", "Confira a publicação vinculada e a data de recebimento.");
            boolean inWindow = at.isBefore(consultation.endExclusive());
            require(!inWindow || !consultation.noInWindowComments(), "RESUMO_DIVERGENTE",
                    "O resumo oficial registrou ausência de manifestações. Confira a divergência antes de prosseguir.");
            UUID commentId = UUID.randomUUID();
            UUID event = audit.record(connection, project, actor, "TX-E3-20", "PERMITIDO", commentId.toString(),
                    Map.of("consultation", id, "proof", input.proof(), "effectiveAt", at, "issuer", "PUB"));
            var capture = new Capture("PUB", HUB, input.proof(), at, actor, event);
            var comment = new Comment(commentId, 1, id, input.proof().officialId(), "APRESENTADO", input.topic(), input.body(),
                    inWindow ? "NA_JANELA" : "FORA_DA_JANELA", inWindow ? null : at.plusMonths(12),
                    capture, null, null, List.of(), List.of(), event);
            update(connection, "INSERT INTO pilot_consultation_comment VALUES (?,?,?,?,?::jsonb,?)", project,
                    commentId, id, 1, audit.encode(comment), input.contact());
            var response = audit.decode(audit.encode(comment));
            evidence.save(connection, project, id, capture, digest, response);
            return response;
        });
    }

    List<VisibleComment> comments(PilotAccess.Scope scope, String actor, UUID id) {
        return database.transaction(connection -> {
            get(connection, scope.project(), id);
            String restriction = scope.has(PilotAccess.VVB) ? actor : null;
            return query(connection, """
                    SELECT state,contact FROM pilot_consultation_comment WHERE project_id=? AND consultation_id=?
                    AND (CAST(? AS text) IS NULL OR state->>'vvb'=?)
                    ORDER BY (state->'received'->>'effectiveAt')::timestamptz,id LIMIT 100
                    """, row -> new VisibleComment(decode(row.getString(1), Comment.class),
                    scope.has(PilotAccess.PP) ? row.getString(2) : null), scope.project(), id, restriction, restriction);
        });
    }

    JsonNode commentAction(PilotAccess.Scope scope, String actor, String key, UUID id, int expected, CommentAction input) {
        if (input == null || input.transitionId() == null || !List.of("TX-E3-21", "TX-E3-22", "TX-E3-23", "VINCULAR_VVB")
                .contains(input.transitionId())) throw invalid("ACAO_INVALIDA", "Selecione uma ação da manifestação.");
        var at = local(input.effectiveAt());
        return commands.execute(scope, actor, input.transitionId(), key, audit.encode(List.of(id, expected, input)), id.toString(),
                connection -> apply(connection, scope.project(), actor, id, expected, input, at));
    }

    private Object apply(Connection connection, String project, String actor, UUID id, int expected, CommentAction input,
                         OffsetDateTime at) throws SQLException {
        intake.lockProject(connection, project);
        var old = first(connection, "SELECT state FROM pilot_consultation_comment WHERE project_id=? AND id=?",
                row -> decode(row.getString(1), Comment.class), project, id);
        if (old == null) throw PilotAccess.invisible();
        var step = input.transitionId();
        boolean forwarding = "TX-E3-21".equals(step);
        boolean evaluating = "TX-E3-23".equals(step);
        boolean official = forwarding || evaluating;
        String namespace = evaluating ? VVB : VERRA_CONSULTATION;
        String authority = forwarding ? VERRA : evaluating ? issuer(connection, project, old, actor) : "PP";
        String digest = null;
        if (official) {
            verify(step, input.proof());
            digest = evidence.digest(Arrays.asList(step, input.proof().target(), at.toInstant(), input.vvb(), input.body(), input.result()));
            var replay = evidence.repeated(connection, project, id, authority, namespace, input.proof(), digest);
            if (replay != null) return replay;
            require(old.target().equals(input.proof().target()), "ATO_DE_OUTRA_MANIFESTACAO", "Confira o identificador da manifestação.");
        }
        PilotCommands.requireVersion(expected, old.version());
        var consultation = get(connection, project, old.consultation());
        var responses = new java.util.ArrayList<>(old.responses());
        var evaluations = new java.util.ArrayList<>(old.evaluations());
        var forwarded = old.forwarded();
        String vvb = old.vvb();
        String state = old.state();
        OffsetDateTime lastResponse = responses.isEmpty() ? null : responses.getLast().effectiveAt();
        OffsetDateTime lastEvaluation = evaluations.isEmpty() ? null : evaluations.getLast().capture().effectiveAt();
        require(!at.isBefore(old.received().effectiveAt()) && (forwarded == null || !at.isBefore(forwarded.effectiveAt()))
                        && (lastResponse == null || !at.isBefore(lastResponse)) && (lastEvaluation == null || !at.isBefore(lastEvaluation)),
                "DATA_RETROATIVA", "A data efetiva precisa respeitar os marcos da manifestação.");
        if (forwarding) {
            require("APRESENTADO".equals(state) && (!"NA_JANELA".equals(old.window()) || !at.isBefore(consultation.endExclusive())),
                    "ENCAMINHAMENTO_INDISPONIVEL", "Para manifestações da janela, confirme o encaminhamento após os trinta dias.");
            vvb = optionalVvb(connection, project, input.vvb());
            state = "ENCAMINHADO";
        } else if ("VINCULAR_VVB".equals(step)) {
            require(forwarded != null && vvb == null && input.vvb() != null && ASSIGNMENT_PROOF.equals(input.support()),
                    "VINCULO_INDISPONIVEL", "Informe o VVB do encaminhamento com prova de vinculação.");
            vvb = optionalVvb(connection, project, input.vvb());
        } else if (evaluating) {
            require("RESPONDIDO".equals(state) && input.result() != null && IntakeRules.text(input.body()),
                    "AVALIACAO_INDISPONIVEL", "Vincule a avaliação independente da resposta e seu resultado.");
            state = input.result() == Result.SUFICIENTE ? "AVALIADO" : "COM_ACHADO";
        } else {
            require(List.of("ENCAMINHADO", "COM_ACHADO").contains(state) && IntakeRules.text(input.body()) && RESPONSE_PROOF.equals(input.support()),
                    "RESPOSTA_INDISPONIVEL", "Registre resposta e prova após o encaminhamento ou achado.");
            responses.add(new Response(responses.size() + 1, input.body(), at, actor, old.responseDue() != null && at.isAfter(old.responseDue()), input.support()));
            state = "RESPONDIDO";
        }
        var facts = new java.util.LinkedHashMap<String, Object>();
        facts.put("consultation", old.consultation()); facts.put("from", old.state()); facts.put("to", state);
        facts.put("authority", authority); facts.put("effectiveAt", at); facts.put("evidence", official ? input.proof() : input.support());
        UUID event = audit.record(connection, project, actor, step, "PERMITIDO", id.toString(), facts);
        Capture capture = official ? new Capture(authority, namespace, input.proof(), at, actor, event) : null;
        if (forwarding) forwarded = capture;
        if (evaluating) evaluations.add(new Evaluation(input.result(), input.body(), responses.getLast().version(), capture));
        var next = new Comment(id, old.version() + 1, old.consultation(), old.target(), state, old.topic(), old.body(), old.window(),
                old.responseDue(), old.received(), forwarded, vvb, List.copyOf(responses), List.copyOf(evaluations), event);
        update(connection, "UPDATE pilot_consultation_comment SET version=?,state=?::jsonb WHERE project_id=? AND id=?",
                next.version(), audit.encode(next), project, id);
        var result = audit.decode(audit.encode(next));
        if (official) evidence.save(connection, project, id, capture, digest, result);
        return result;
    }

    private String issuer(Connection connection, String project, Comment old, String actor) throws SQLException {
        if (!Objects.equals(old.vvb(), actor)) throw PilotAccess.invisible();
        return vvbIssuer(connection, project, actor);
    }

    private String optionalVvb(Connection connection, String project, String id) throws SQLException {
        if (id == null) return null;
        vvbIssuer(connection, project, id);
        return id;
    }

    private String vvbIssuer(Connection connection, String project, String id) throws SQLException {
        var issuer = first(connection, """
                SELECT i.external_issuer FROM pilot_identity i JOIN pilot_membership m ON m.identity_id=i.id
                WHERE i.id=? AND m.project_id=? AND 'VVB'=ANY(m.roles)
                  AND m.valid_from<=clock_timestamp() AND m.valid_until>clock_timestamp()
                """, row -> row.getString(1), id, project);
        if (issuer == null) throw PilotAccess.invisible();
        return issuer;
    }

    private Consultation get(Connection connection, String project, UUID id) throws SQLException {
        var item = first(connection, "SELECT state FROM pilot_consultation WHERE project_id=? AND id=?",
                row -> decode(row.getString(1), Consultation.class), project, id);
        if (item == null) throw PilotAccess.invisible();
        return item;
    }

    private <T> T decode(String raw, Class<T> type) {
        try { return mapper.readValue(raw, type); }
        catch (java.io.IOException error) { throw new IllegalStateException("Falha ao ler a consulta", error); }
    }
}
