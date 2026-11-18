package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ComplianceDecisions {
    enum Outcome { NEEDS_INFORMATION, APPROVED, REJECTED, REVOKED }
    record Input(Outcome decision, String reason, UUID documentId, Instant validUntil) {}
    record Decision(int version, Outcome decision, String reason, UUID documentId,
                    Instant validUntil, Instant recordedAt) {}
    record GrantedPower(ProjectAuthorityRequests.Power power, UUID requestId, int decisionVersion, Instant validUntil) {}

    private static final String COLUMNS = "version, decision, reason, document_id, valid_until, recorded_at";
    private static final String FROM = """
             FROM app_compliance_power_decision WHERE project_id = ? AND request_id = ?
            """;

    private final PilotDatabase database;
    private final ComplianceStaff staff;

    ComplianceDecisions(PilotDatabase database, ComplianceStaff staff) {
        this.database = database;
        this.staff = staff;
    }

    List<Decision> history(String token, String project, UUID request) {
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            ComplianceQueue.requireCase(connection, actor, project, request, false);
            return history(connection, project, request);
        });
    }

    List<Decision> decide(String token, String project, UUID request, String version, String key, Input submitted) {
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            ComplianceQueue.requireCase(connection, actor, project, request, true);
            Input input = normalize(submitted);
            int expected = PilotCommands.expected(version);
            String command = PilotCommands.key(key);
            Decision replay = first(connection, "SELECT " + COLUMNS + FROM + " AND decided_by = ? AND command_key = ?",
                    ComplianceDecisions::decision, project, request, actor.id(), command);
            if (replay != null) {
                if (replay.version() != expected + 1 || !new Input(replay.decision(), replay.reason(), replay.documentId(),
                        replay.validUntil()).equals(input)) {
                    throw new PilotProblem(409, "CHAVE_REUSADA", "command", "CF-IMP-028",
                            "A chave já identifica outra decisão.");
                }
                return history(connection, project, request);
            }
            Decision previous = latest(connection, project, request);
            PilotCommands.requireVersion(expected, previous == null ? 0 : previous.version());
            if (!allowed(previous, input.decision())) {
                throw new PilotProblem(409, "DECISAO_INCOMPATIVEL", "decision", "CF-IMP-028",
                        "A decisão escolhida não está disponível para o estado atual da solicitação.");
            }
            OffsetDateTime now = first(connection, "SELECT statement_timestamp()", row -> row.getObject(1, OffsetDateTime.class));
            if (input.decision() == Outcome.APPROVED) {
                Boolean valid = first(connection, """
                        SELECT EXISTS (
                            SELECT 1 FROM app_compliance_evidence WHERE project_id = ? AND request_id = ? AND id = ?
                        )
                        """, row -> row.getBoolean(1), project, request, input.documentId());
                if (!Boolean.TRUE.equals(valid)) throw invalid("documentId");
                if (!input.validUntil().isAfter(now.toInstant())) throw invalid("validUntil");
            }
            update(connection, """
                    INSERT INTO app_compliance_power_decision(project_id,request_id,version,decided_by,decision,
                        reason,document_id,valid_until,command_key,recorded_at)
                    VALUES(?,?,?,?,?,?,?,?,?,?)
                    """, project, request, expected + 1, actor.id(), input.decision().name(), input.reason(),
                    input.documentId(), utc(input.validUntil()), command, now);
            return history(connection, project, request);
        });
    }

    static List<GrantedPower> active(Connection connection, String project, UUID beneficiary) throws SQLException {
        return query(connection, """
                SELECT r.requested_power, r.request_id, d.version, d.valid_until
                FROM app_project_authority_request_revision r
                JOIN app_project p ON p.id = r.project_id
                JOIN app_organization_membership m ON m.organization_id = p.organization_id AND m.identity_id = r.actor_id
                JOIN LATERAL (SELECT version, decision, valid_until FROM app_compliance_power_decision
                    WHERE project_id = r.project_id AND request_id = r.request_id ORDER BY version DESC LIMIT 1) d ON true
                WHERE r.project_id = ? AND r.actor_id = ? AND r.version = 1 AND d.decision = 'APPROVED'
                  AND d.valid_until > statement_timestamp() AND m.revoked_at IS NULL
                  AND m.valid_from <= statement_timestamp() AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
                  AND NOT EXISTS (SELECT 1 FROM app_project_authority_request_revision newer
                    WHERE newer.project_id = r.project_id AND newer.request_id = r.request_id AND newer.version > 1)
                ORDER BY r.requested_power
                """, row -> new GrantedPower(ProjectAuthorityRequests.Power.valueOf(row.getString(1)),
                row.getObject(2, UUID.class), row.getInt(3), instant(row, "valid_until")), project, beneficiary);
    }

    static Decision latest(Connection connection, String project, UUID request) throws SQLException {
        return first(connection, "SELECT " + COLUMNS + FROM + " ORDER BY version DESC LIMIT 1",
                ComplianceDecisions::decision, project, request);
    }

    static java.util.Map<UUID, Decision> latestForProject(Connection connection, String project) throws SQLException {
        var entries = query(connection, "SELECT DISTINCT ON (request_id) request_id, " + COLUMNS
                + " FROM app_compliance_power_decision WHERE project_id = ? ORDER BY request_id, version DESC",
                row -> java.util.Map.entry(row.getObject("request_id", UUID.class), decision(row)), project);
        return entries.stream().collect(java.util.stream.Collectors.toMap(java.util.Map.Entry::getKey, java.util.Map.Entry::getValue));
    }

    private static List<Decision> history(Connection connection, String project, UUID request) throws SQLException {
        return query(connection, "SELECT " + COLUMNS + FROM + " ORDER BY version DESC",
                ComplianceDecisions::decision, project, request);
    }

    private static Decision decision(ResultSet row) throws SQLException {
        return new Decision(row.getInt("version"), Outcome.valueOf(row.getString("decision")), row.getString("reason"),
                row.getObject("document_id", UUID.class), instant(row, "valid_until"), instant(row, "recorded_at"));
    }

    private static boolean allowed(Decision previous, Outcome next) {
        if (previous == null || previous.decision() == Outcome.NEEDS_INFORMATION) return next != Outcome.REVOKED;
        return previous.decision() == Outcome.APPROVED && next == Outcome.REVOKED;
    }

    private static Input normalize(Input input) {
        if (input == null || input.decision() == null) throw invalid("decision");
        String reason = input.reason() == null ? "" : input.reason().strip();
        if (reason.isBlank() || reason.codePointCount(0, reason.length()) > 600
                || reason.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) throw invalid("reason");
        if (input.decision() == Outcome.APPROVED && (input.documentId() == null || input.validUntil() == null)) {
            throw invalid("validUntil");
        }
        if (input.decision() != Outcome.APPROVED && (input.documentId() != null || input.validUntil() != null)) {
            throw invalid("decision");
        }
        Instant expiry = input.validUntil() == null ? null : input.validUntil().truncatedTo(java.time.temporal.ChronoUnit.MICROS);
        return new Input(input.decision(), reason, input.documentId(), expiry);
    }

    private static Instant instant(ResultSet row, String column) throws SQLException {
        OffsetDateTime value = row.getObject(column, OffsetDateTime.class);
        return value == null ? null : value.toInstant();
    }

    private static OffsetDateTime utc(Instant value) {
        return value == null ? null : value.atOffset(ZoneOffset.UTC);
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "DECISAO_INVALIDA", field, "CF-IMP-028",
                "Informe o parecer e, para aprovar, selecione um comprovante do protocolo e uma vigência futura.");
    }
}
