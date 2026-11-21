package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Records an immutable calculation attempt for one dynamic project period.
 * The HTTP body carries version identifiers only. IR, Dy and EFfuel have no
 * stored project value, so the API path persists BLOCKED and does not call
 * {@link OpenGridCalculationMath}. Candidate rows stay usable_for_e8=false.
 */
@ApplicationScoped
class OpenE8ProjectRuns {
    private static final String RULE = "CF-OPEN-008";

    record Request(UUID energyBindingId, UUID fleetSelectionId) {}

    record Attempt(UUID id, String projectId, String period, int version,
                   String state, boolean stale, boolean canIssueVcu, String engine,
                   UUID energyBindingId, UUID fleetSelectionId, String inputSha256,
                   List<OpenCalculationPreflight.Gate> gates,
                   List<OpenCalculationTransparency.Stage> equationPlan,
                   OpenE8RunDecision.Memory memory, OffsetDateTime createdAt) {}

    record Listing(String projectId, String period, boolean canRecord, List<Attempt> runs) {}

    record Outcome(Attempt view, boolean created) {}

    private record Stored(UUID id, String project, String period, int version,
                          UUID predecessor, UUID binding, UUID fleet, String sha,
                          String state, boolean canIssueVcu, String engine,
                          String afec, String mpg, String be, String pe, String le,
                          String dy, String er, String erDisplay, String trace,
                          String gates, OffsetDateTime created) {}

    private final DynamicProjectScope scope;

    OpenE8ProjectRuns(DynamicProjectScope scope) { this.scope = scope; }

    Listing list(String cookie, String project, String period) {
        return scope.read(cookie, project, "VIEW_OPEN_E8_RUN", (c, ctx) -> {
            String code = OpenMonitoringDossiers.code(period);
            var rows = query(c, STORED + """
                WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 20
                """, OpenE8ProjectRuns::stored, project, code);
            var runs = new ArrayList<Attempt>();
            for (var row : rows) runs.add(view(c, row));
            return new Listing(project, code, ctx.actions().contains("RECORD_OPEN_E8_RUN"),
                List.copyOf(runs));
        });
    }

    Attempt detail(String cookie, String project, String period, UUID id) {
        return scope.read(cookie, project, "VIEW_OPEN_E8_RUN", (c, ctx) -> {
            String code = OpenMonitoringDossiers.code(period);
            Stored row = first(c, STORED + """
                WHERE project_id=? AND period_code=? AND id=?
                """, OpenE8ProjectRuns::stored, project, code, id);
            if (row == null) throw unavailable();
            return view(c, row);
        });
    }

    Outcome record(String cookie, String project, String period, String key,
                   String match, Request request) {
        return scope.write(cookie, project, "RECORD_OPEN_E8_RUN", (c, ctx) -> {
            if (!ctx.owner() && !"PREP".equals(ctx.role())) throw unavailable();
            String code = OpenMonitoringDossiers.code(period);
            String command = PilotCommands.key(key);
            int expected = PilotCommands.expected(match);
            UUID requestedBinding = request == null ? null : request.energyBindingId();
            UUID requestedFleet = request == null ? null : request.fleetSelectionId();
            UUID binding = resolveBinding(c, project, code, requestedBinding);
            UUID fleet = resolveFleet(c, project, code, requestedFleet);
            byte[] fingerprint = AccessSecrets.digest(material(project, code, binding, fleet));
            Stored replay = commandRun(c, ctx.actor().id(), command, project, fingerprint);
            if (replay != null) return new Outcome(view(c, replay), false);

            Stored latest = latest(c, project, code);
            boolean sameSet = latest != null && MessageDigestEquals(latest.sha(), fingerprint);
            if (sameSet) {
                PilotCommands.requireVersion(expected, latest.version());
                remember(c, ctx.actor().id(), command, project, latest.id(), fingerprint);
                return new Outcome(view(c, latest), false);
            }
            int current = latest == null ? 0 : latest.version();
            PilotCommands.requireVersion(expected, current);
            if (current >= 100000000) throw conflict("Limite de versões atingido.");

            var set = readSet(c, project, code, binding, fleet, ctx.actor().id());
            var decision = OpenE8RunDecision.decide(set);
            UUID id = UUID.randomUUID();
            boolean reviewed = "REVIEWED_INTERNAL".equals(decision.state());
            OpenE8RunDecision.Memory memory = decision.memory();
            update(c, """
                INSERT INTO app_open_e8_run
                (id,project_id,organization_id,environment,period_code,version,
                 predecessor_id,energy_binding_id,fleet_selection_id,input_sha256,
                 state,engine,afec,mpg,be_tco2e,pe_tco2e,le_tco2e,dy,er_tco2e,
                 er_display,trace_json,gate_codes,created_by)
                VALUES (?,?,?,?,?,?,
                  NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, ?,
                  ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, '')::jsonb, string_to_array(?, E'\\n'), ?)
                """, id, project, ctx.organization(), ctx.environment(), code, current + 1,
                text(latest == null ? null : latest.id()), text(binding), text(fleet),
                fingerprint, decision.state(),
                reviewed ? memory.engine() : "",
                reviewed ? memory.afecKwhPer100Miles() : "",
                reviewed ? memory.mpgUs() : "",
                reviewed ? memory.beTco2e() : "",
                reviewed ? memory.peTco2e() : "",
                reviewed ? memory.leTco2e() : "",
                reviewed ? memory.dy() : "",
                reviewed ? memory.erTco2e() : "",
                reviewed ? memory.erDisplay() : "",
                reviewed ? OpenE8RunDecision.traceJson(memory) : "",
                OpenE8RunDecision.encode(decision.gates()), ctx.actor().id());
            remember(c, ctx.actor().id(), command, project, id, fingerprint);
            Stored stored = first(c, STORED + " WHERE project_id=? AND id=?",
                OpenE8ProjectRuns::stored, project, id);
            return new Outcome(view(c, stored), true);
        });
    }

    /**
     * Project operands the server can actually read. Completeness stays false:
     * V50 stores no normalized IR or Dy, and there is no project EFfuel pin.
     */
    private static OpenE8RunDecision.OperandSet readSet(Connection c, String project,
            String period, UUID binding, UUID fleet, UUID actor) throws SQLException {
        var gates = new ArrayList<OpenCalculationPreflight.Gate>();
        boolean stale = false;
        if (binding == null) {
            gates.add(OpenE8RunDecision.gate("ED", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EC", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EF_GRID", "MISSING"));
        } else {
            if (OpenE8InputBindings.state(c, project, period, binding).contains("STALE"))
                stale = true;
            Integer factors = first(c, """
                SELECT count(DISTINCT efel_tco2e_per_kwh)::int
                FROM app_open_e8_input_binding_hour
                WHERE project_id=? AND binding_id=?
                """, r -> r.getInt(1), project, binding);
            if (factors == null || factors != 1)
                gates.add(OpenE8RunDecision.gate("HOURLY_EFEL_NOT_UNIFORM", "REVIEW_REQUIRED"));
            gates.add(OpenE8RunDecision.gate("ED", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("EC", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("EF_GRID", "NOT_ADMITTED"));
        }
        if (fleet == null) {
            gates.add(OpenE8RunDecision.gate("EVR", "MISSING"));
            gates.add(OpenE8RunDecision.gate("AFEC_MPG", "MISSING"));
        } else {
            if (fleetMoved(c, project, period, fleet)) stale = true;
            gates.add(OpenE8RunDecision.gate("EVR", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("AFEC_MPG", "NOT_ADMITTED"));
        }
        gates.add(OpenE8RunDecision.gate("EF_FUEL", "MISSING"));
        gates.add(OpenE8RunDecision.gate("IR", "MISSING"));
        gates.add(OpenE8RunDecision.gate("DY", "MISSING"));
        gates.add(OpenE8RunDecision.gate("NORMATIVE_PACK", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("CARBON_RIGHTS", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("TECHNICAL_BRANCH", "NOT_ADMITTED"));
        return new OpenE8RunDecision.OperandSet(false, stale, authored(c, project, binding, fleet, actor),
            List.copyOf(gates), null);
    }

    private static Attempt view(Connection c, Stored row) throws SQLException {
        if (row.canIssueVcu())
            throw new IllegalStateException("Run de projeto admite VCU.");
        boolean stale = moved(c, row);
        var gates = new ArrayList<>(decode(row.gates()));
        if (stale) gates.add(OpenE8RunDecision.gate("RUN_REFERENCES", "STALE"));
        OpenE8RunDecision.Memory memory = memory(row);
        return new Attempt(row.id(), row.project(), row.period(), row.version(),
            OpenE8RunDecision.responseState(row.state(), stale), stale, false, row.engine(),
            row.binding(), row.fleet(), row.sha(), List.copyOf(gates),
            OpenCalculationTransparency.plan(gates), memory, row.created());
    }

    private static OpenE8RunDecision.Memory memory(Stored row) {
        if (!"REVIEWED_INTERNAL".equals(row.state()) || row.trace() == null) return null;
        return new OpenE8RunDecision.Memory(row.engine(), "PURE_MATH_TEST_ONLY_NOT_ADMITTED",
            false, row.afec(), row.mpg(), row.be(), row.pe(), row.le(), row.dy(),
            row.er(), row.erDisplay(), OpenE8RunDecision.parseTrace(row.trace()));
    }

    private static List<OpenCalculationPreflight.Gate> decode(String stored) {
        if (stored == null || stored.isBlank()) return List.of();
        var gates = new ArrayList<OpenCalculationPreflight.Gate>();
        for (String line : stored.split("\n")) {
            int cut = line.indexOf('=');
            if (cut < 1) continue;
            gates.add(OpenE8RunDecision.gate(line.substring(0, cut), line.substring(cut + 1)));
        }
        return gates;
    }

    private static boolean moved(Connection c, Stored row) throws SQLException {
        UUID bindingNow = latestBinding(c, row.project(), row.period());
        UUID fleetNow = latestFleet(c, row.project(), row.period());
        if (!Objects.equals(row.binding(), bindingNow) || !Objects.equals(row.fleet(), fleetNow))
            return true;
        if (row.binding() != null && OpenE8InputBindings.state(c, row.project(), row.period(),
            row.binding()).contains("STALE")) return true;
        return row.fleet() != null && fleetMoved(c, row.project(), row.period(), row.fleet());
    }

    private static boolean fleetMoved(Connection c, String project, String period, UUID fleet)
            throws SQLException {
        UUID latest = latestFleet(c, project, period);
        if (!fleet.equals(latest)) return true;
        String state = OpenE8FleetSelections.currentState(c, project, period);
        return state != null && state.contains("STALE");
    }

    private static boolean authored(Connection c, String project, UUID binding, UUID fleet,
                                    UUID actor) throws SQLException {
        if (binding != null) {
            UUID author = first(c, """
                SELECT created_by FROM app_open_e8_input_binding_candidate
                WHERE project_id=? AND id=?
                """, r -> r.getObject(1, UUID.class), project, binding);
            if (actor.equals(author)) return true;
        }
        if (fleet != null) {
            UUID author = first(c, """
                SELECT published_by FROM app_open_e8_fleet_selection
                WHERE project_id=? AND id=?
                """, r -> r.getObject(1, UUID.class), project, fleet);
            if (actor.equals(author)) return true;
        }
        return false;
    }

    private static UUID resolveBinding(Connection c, String project, String period, UUID requested)
            throws SQLException {
        if (requested == null) return latestBinding(c, project, period);
        UUID found = first(c, """
            SELECT id FROM app_open_e8_input_binding_candidate
            WHERE project_id=? AND period_code=? AND id=?
            """, r -> r.getObject(1, UUID.class), project, period, requested);
        if (found == null) throw unavailable();
        return found;
    }

    private static UUID resolveFleet(Connection c, String project, String period, UUID requested)
            throws SQLException {
        if (requested == null) return latestFleet(c, project, period);
        UUID found = first(c, """
            SELECT id FROM app_open_e8_fleet_selection
            WHERE project_id=? AND period_code=? AND id=?
            """, r -> r.getObject(1, UUID.class), project, period, requested);
        if (found == null) throw unavailable();
        return found;
    }

    private static UUID latestBinding(Connection c, String project, String period) throws SQLException {
        return first(c, """
            SELECT id FROM app_open_e8_input_binding_candidate
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """, r -> r.getObject(1, UUID.class), project, period);
    }

    private static UUID latestFleet(Connection c, String project, String period) throws SQLException {
        return first(c, """
            SELECT id FROM app_open_e8_fleet_selection
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """, r -> r.getObject(1, UUID.class), project, period);
    }

    private static Stored commandRun(Connection c, UUID actor, String key, String project,
                                     byte[] fingerprint) throws SQLException {
        Object[] old = first(c, """
            SELECT project_id, run_id, fingerprint
            FROM app_open_e8_run_command
            WHERE actor_id=? AND command_key=?
            """, r -> new Object[]{r.getString(1), r.getObject(2, UUID.class), r.getBytes(3)},
            actor, key);
        if (old == null) return null;
        if (!project.equals(old[0]) || !MessageDigestEquals(fingerprint, (byte[]) old[2]))
            throw conflict("A chave de idempotência foi usada com outro conjunto.");
        Stored row = first(c, STORED + " WHERE project_id=? AND id=?",
            OpenE8ProjectRuns::stored, project, (UUID) old[1]);
        if (row == null) throw unavailable();
        return row;
    }

    private static void remember(Connection c, UUID actor, String key, String project,
                                 UUID run, byte[] fingerprint) throws SQLException {
        update(c, """
            INSERT INTO app_open_e8_run_command
            (actor_id,command_key,project_id,run_id,fingerprint)
            VALUES (?,?,?,?,?)
            """, actor, key, project, run, fingerprint);
    }

    private static Stored latest(Connection c, String project, String period) throws SQLException {
        return first(c, STORED + """
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """, OpenE8ProjectRuns::stored, project, period);
    }

    private static String material(String project, String period, UUID binding, UUID fleet) {
        return "E8-RUN-SET-V1|" + project + "|" + period + "|"
            + (binding == null ? "-" : binding) + "|" + (fleet == null ? "-" : fleet);
    }

    private static String text(UUID id) { return id == null ? "" : id.toString(); }

    private static boolean MessageDigestEquals(String hex, byte[] fingerprint) {
        return java.security.MessageDigest.isEqual(HexFormat.of().parseHex(hex), fingerprint);
    }

    private static boolean MessageDigestEquals(byte[] left, byte[] right) {
        return java.security.MessageDigest.isEqual(left, right);
    }

    private static final String STORED = """
        SELECT id,project_id,period_code,version,predecessor_id,energy_binding_id,
          fleet_selection_id,encode(input_sha256,'hex'),state,can_issue_vcu,engine,
          afec,mpg,be_tco2e,pe_tco2e,le_tco2e,dy,er_tco2e,er_display,trace_json::text,
          array_to_string(gate_codes, E'\\n'),created_at
        FROM app_open_e8_run
        """;

    private static Stored stored(java.sql.ResultSet r) throws SQLException {
        return new Stored(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
            r.getInt(4), r.getObject(5, UUID.class), r.getObject(6, UUID.class),
            r.getObject(7, UUID.class), r.getString(8), r.getString(9), r.getBoolean(10),
            r.getString(11), r.getString(12), r.getString(13), r.getString(14),
            r.getString(15), r.getString(16), r.getString(17), r.getString(18),
            r.getString(19), r.getString(20), r.getString(21),
            r.getObject(22, OffsetDateTime.class));
    }

    private static PilotProblem unavailable() {
        return new PilotProblem(404, "E8_RUN_UNAVAILABLE", "run", RULE, "Tentativa indisponível.");
    }

    private static PilotProblem conflict(String reason) {
        return new PilotProblem(409, "E8_RUN_CONFLICT", "run", RULE, reason);
    }
}
