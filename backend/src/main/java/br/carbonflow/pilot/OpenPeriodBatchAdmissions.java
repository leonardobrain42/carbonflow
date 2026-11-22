package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Independent admission of one measurement batch already frozen in a period
 * snapshot. Line QA and the snapshot itself are not this admission. The
 * decision never authorizes E8.
 */
@ApplicationScoped
class OpenPeriodBatchAdmissions {
    private static final String RULE = "CF-OPEN-009";

    record Decision(UUID batchId, String outcome, String reason) {}
    record Result(View view, boolean created) {}
    record View(UUID id, UUID snapshotId, UUID batchId, int lines, String edKwh, String ecKwh,
                int version, String outcome, String reason, String state, boolean stale,
                boolean usableForE8, OffsetDateTime admittedAt) {}

    private record Totals(UUID batch, int lines, BigDecimal ed, BigDecimal ec) {}
    private record Frozen(OffsetDateTime from, OffsetDateTime until, String sha,
                          UUID createdBy) {}
    private record Stored(UUID id, UUID snapshot, UUID batch, int version, String outcome,
                          String reason, UUID admittedBy, OffsetDateTime admittedAt) {}

    private final DynamicProjectScope scope;

    OpenPeriodBatchAdmissions(DynamicProjectScope scope) {
        this.scope = scope;
    }

    List<View> list(String cookie, String project, UUID snapshot) {
        return scope.read(cookie, project, "VIEW_OPEN_MONITORING", (connection, context) -> {
            Frozen frozen = frozen(connection, project, snapshot);
            if (frozen == null) throw hidden();
            boolean stale = stale(connection, project, frozen);
            var rows = new ArrayList<View>();
            for (Totals totals : totals(connection, project, snapshot)) {
                Stored latest = latest(connection, project, snapshot, totals.batch());
                rows.add(view(snapshot, totals, latest, stale));
            }
            return List.copyOf(rows);
        });
    }

    Result decide(String cookie, String project, UUID snapshot, String key, String match, Decision input) {
        return scope.write(cookie, project, "ADMIT_OPEN_PERIOD_BATCH", (connection, context) -> {
            if (context.owner() || !"REV".equals(context.role())) throw hidden();
            if (input == null || input.batchId() == null || input.outcome() == null
                    || !List.of("ADMITTED_FOR_PERIOD", "REJECTED").contains(input.outcome())
                    || input.reason() == null || input.reason().strip().length() < 15
                    || input.reason().strip().length() > 2000) {
                throw PilotProblem.invalid("ADMISSAO_INVALIDA", "reason", RULE,
                    "Informe o lote, a decisão e uma justificativa entre 15 e 2000 caracteres.");
            }
            Frozen frozen = frozen(connection, project, snapshot);
            Totals totals = totals(connection, project, snapshot).stream()
                .filter(row -> row.batch().equals(input.batchId())).findFirst().orElse(null);
            if (frozen == null || totals == null) throw hidden();
            if (context.actor().id().equals(frozen.createdBy()) || involved(connection, project,
                    snapshot, input.batchId(), context.actor().id())) {
                throw new PilotProblem(403, "ADMISSAO_NAO_INDEPENDENTE", "reviewer", RULE,
                    "Quem enviou o lote, revisou a linha ou congelou o snapshot não admite este lote.");
            }
            int expected = PilotCommands.expected(match);
            String command = PilotCommands.key(key);
            String reason = input.reason().strip();
            byte[] fingerprint = AccessSecrets.digest("OPEN-BATCH-ADMISSION-V1|" + project + "|"
                + snapshot + "|" + input.batchId() + "|" + expected + "|"
                + input.outcome() + "|" + reason);
            Stored replay = first(connection, """
                SELECT a.id,a.snapshot_id,a.batch_id,a.version,a.outcome,a.reason,
                       a.admitted_by,a.admitted_at
                FROM app_open_period_batch_admission_command c
                JOIN app_open_period_batch_admission a
                  ON a.project_id=c.project_id AND a.id=c.admission_id
                WHERE c.actor_id=? AND c.command_key=?
                """, OpenPeriodBatchAdmissions::stored, context.actor().id(), command);
            if (replay != null) {
                byte[] previous = first(connection, """
                    SELECT fingerprint FROM app_open_period_batch_admission_command
                    WHERE actor_id=? AND command_key=?
                    """, r -> r.getBytes(1), context.actor().id(), command);
                if (!snapshot.equals(replay.snapshot()) || !input.batchId().equals(replay.batch())
                        || previous == null || !MessageDigest.isEqual(fingerprint, previous)) {
                    throw conflict("A chave de idempotência foi usada com outra admissão.");
                }
                return new Result(view(snapshot, totals, replay, stale(connection, project, frozen)), false);
            }
            if (stale(connection, project, frozen)) {
                throw conflict("O snapshot ficou desatualizado. A admissão nova exige outro congelamento.");
            }
            Stored current = latest(connection, project, snapshot, input.batchId());
            if (current != null && current.version() == expected + 1
                    && current.admittedBy().equals(context.actor().id())
                    && current.outcome().equals(input.outcome()) && current.reason().equals(reason)) {
                return new Result(view(snapshot, totals, current, false), false);
            }
            PilotCommands.requireVersion(expected, current == null ? 0 : current.version());
            if (expected >= 100000000) throw conflict("Limite de versões atingido.");
            UUID id = UUID.randomUUID();
            update(connection, """
                INSERT INTO app_open_period_batch_admission
                 (id,project_id,organization_id,environment,snapshot_id,batch_id,version,
                  outcome,reason,snapshot_sha256,admitted_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?)
                """, id, project, context.organization(), context.environment(), snapshot,
                input.batchId(), expected + 1, input.outcome(), reason,
                HexFormat.of().parseHex(frozen.sha()), context.actor().id());
            update(connection, """
                INSERT INTO app_open_period_batch_admission_command
                 (actor_id,command_key,project_id,snapshot_id,admission_id,fingerprint)
                VALUES (?,?,?,?,?,?)
                """, context.actor().id(), command, project, snapshot, id, fingerprint);
            Stored stored = latest(connection, project, snapshot, input.batchId());
            return new Result(view(snapshot, totals, stored, false), true);
        });
    }

    private static View view(UUID snapshot, Totals totals, Stored stored, boolean stale) {
        String state = stored == null ? (stale ? "STALE" : "MISSING")
            : stale ? "STALE" : stored.outcome();
        return new View(stored == null ? null : stored.id(), snapshot, totals.batch(), totals.lines(),
            plain(totals.ed()), plain(totals.ec()), stored == null ? 0 : stored.version(),
            stored == null ? null : stored.outcome(), stored == null ? null : stored.reason(),
            state, stale, false, stored == null ? null : stored.admittedAt());
    }

    private static String plain(BigDecimal value) {
        return value == null || value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static boolean stale(Connection connection, String project, Frozen frozen) throws SQLException {
        var qa = OpenMeasurementReviewQa.evaluate(connection, project, frozen.from(), frozen.until());
        String current = OpenMeasurementReviewQa.hash(project, frozen.from(), frozen.until(),
            qa.accepted(), OpenMonitoringEvents.impacting(connection, project, frozen.from(), frozen.until()));
        return !current.equals(frozen.sha());
    }

    private static boolean involved(Connection connection, String project, UUID snapshot, UUID batch,
                                    UUID actor) throws SQLException {
        Boolean submitter = first(connection, """
            SELECT true FROM app_open_measurement_batch
            WHERE project_id=? AND id=? AND submitted_by=?
            """, r -> true, project, batch, actor);
        if (Boolean.TRUE.equals(submitter)) return true;
        Boolean reviewer = first(connection, """
            SELECT true
            FROM app_open_period_qa_snapshot_line l
            JOIN app_open_measurement_review r
              ON r.project_id=l.project_id AND r.batch_id=l.batch_id
             AND r.source_line=l.source_line AND r.version=l.review_version
            WHERE l.project_id=? AND l.snapshot_id=? AND l.batch_id=? AND r.reviewed_by=?
            """, r -> true, project, snapshot, batch, actor);
        return Boolean.TRUE.equals(reviewer);
    }

    private static Frozen frozen(Connection connection, String project, UUID snapshot) throws SQLException {
        return first(connection, """
            SELECT period_start,period_end,encode(source_sha256,'hex') AS sha,created_by
            FROM app_open_period_qa_snapshot
            WHERE project_id=? AND id=?
            """, r -> new Frozen(r.getObject("period_start", OffsetDateTime.class),
                r.getObject("period_end", OffsetDateTime.class), r.getString("sha"),
                r.getObject("created_by", UUID.class)), project, snapshot);
    }

    private static List<Totals> totals(Connection connection, String project, UUID snapshot) throws SQLException {
        return query(connection, """
            SELECT l.batch_id, count(*) AS lines,
                   COALESCE(sum(c.kwh) FILTER (WHERE c.energy_kind='ED'), 0) AS ed_kwh,
                   COALESCE(sum(c.kwh) FILTER (WHERE c.energy_kind='EC'), 0) AS ec_kwh
            FROM app_open_period_qa_snapshot_line l
            JOIN app_open_measurement_candidate c
              ON c.project_id=l.project_id AND c.batch_id=l.batch_id
             AND c.source_line=l.source_line
            WHERE l.project_id=? AND l.snapshot_id=?
            GROUP BY l.batch_id
            ORDER BY l.batch_id
            """, r -> new Totals(r.getObject("batch_id", UUID.class), r.getInt("lines"),
                r.getBigDecimal("ed_kwh"), r.getBigDecimal("ec_kwh")), project, snapshot);
    }

    private static Stored latest(Connection connection, String project, UUID snapshot, UUID batch)
            throws SQLException {
        return first(connection, """
            SELECT id,snapshot_id,batch_id,version,outcome,reason,admitted_by,admitted_at
            FROM app_open_period_batch_admission
            WHERE project_id=? AND snapshot_id=? AND batch_id=?
            ORDER BY version DESC LIMIT 1
            """, OpenPeriodBatchAdmissions::stored, project, snapshot, batch);
    }

    private static Stored stored(java.sql.ResultSet row) throws SQLException {
        return new Stored(row.getObject("id", UUID.class), row.getObject("snapshot_id", UUID.class),
            row.getObject("batch_id", UUID.class), row.getInt("version"), row.getString("outcome"),
            row.getString("reason"), row.getObject("admitted_by", UUID.class),
            row.getObject("admitted_at", OffsetDateTime.class));
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "ADMISSAO_INDISPONIVEL", "batch", RULE, "Admissão indisponível.");
    }

    private static PilotProblem conflict(String message) {
        return new PilotProblem(409, "ADMISSAO_CONFLITO", "batch", RULE, message);
    }
}
