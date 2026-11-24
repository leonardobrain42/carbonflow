package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import br.carbonflow.pilot.OpenProjectParameterBindings.AnnualPin;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.math.BigDecimal;
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
 * The HTTP body carries version identifiers only. The kernel runs only when
 * the stored ED, EC, single EFel, fleet lines, fossil pin, IR and Dy form one
 * set and the actor did not author those links. The result stays a rehearsal:
 * usableForE8 and canIssueVcu remain false.
 */
@ApplicationScoped
class OpenE8ProjectRuns {
    private static final String RULE = "CF-OPEN-008";

    record Request(UUID energyBindingId, UUID fleetSelectionId) {}

    record ParameterPins(UUID fossilFactor, UUID technologyImprovement, UUID discount) {}

    record Attempt(UUID id, String projectId, String period, int version,
                   String state, boolean stale, boolean canIssueVcu, String engine,
                   UUID energyBindingId, UUID fleetSelectionId, String inputSha256,
                   List<OpenCalculationPreflight.Gate> gates,
                   List<OpenCalculationTransparency.Stage> equationPlan,
                   OpenE8RunDecision.Memory memory, OffsetDateTime createdAt,
                   OpenE8Calendar.Context temporalContext, ParameterPins parameterPins) {}

    record Listing(String projectId, String period, boolean canRecord, List<Attempt> runs) {}

    record Outcome(Attempt view, boolean created) {}

    private record Stored(UUID id, String project, String period, int version,
                          UUID predecessor, UUID binding, UUID fleet,
                          UUID fuelPin, UUID irPin, UUID dyPin, String sha,
                          String state, boolean canIssueVcu, String engine,
                          String afec, String mpg, String be, String pe, String le,
                          String dy, String er, String erDisplay, String trace,
                          String gates, OffsetDateTime created, String temporalContext) {}

    private final DynamicProjectScope scope;
    private final ObjectMapper mapper;

    OpenE8ProjectRuns(DynamicProjectScope scope, ObjectMapper mapper) {
        this.scope = scope;
        this.mapper = mapper;
    }

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
            byte[] requestHash = AccessSecrets.digest("E8-RUN-REQUEST-V1|" + project + "|" + code
                + "|" + id(requestedBinding) + "|" + id(requestedFleet));
            Stored replay = commandRun(c, ctx.actor().id(), command, project, code,
                requestedBinding, requestedFleet, requestHash);
            if (replay != null) return new Outcome(view(c, replay), false);

            UUID binding = resolveBinding(c, project, code, requestedBinding);
            UUID fleet = resolveFleet(c, project, code, requestedFleet);
            Loaded loaded = load(c, project, code, binding, fleet, ctx.actor().id());
            byte[] fingerprint = AccessSecrets.digest(material(project, code, binding, fleet, loaded));

            Stored latest = latest(c, project, code);
            boolean sameSet = latest != null && MessageDigestEquals(latest.sha(), fingerprint);
            if (sameSet) {
                PilotCommands.requireVersion(expected, latest.version());
                remember(c, ctx.actor().id(), command, project, latest.id(), fingerprint, requestHash);
                return new Outcome(view(c, latest), false);
            }
            int current = latest == null ? 0 : latest.version();
            PilotCommands.requireVersion(expected, current);
            if (current >= 100000000) throw conflict("Limite de versões atingido.");

            var decision = OpenE8RunDecision.decide(loaded.set());
            UUID id = UUID.randomUUID();
            boolean reviewed = "REVIEWED_INTERNAL".equals(decision.state());
            OpenE8RunDecision.Memory memory = decision.memory();
            update(c, """
                INSERT INTO app_open_e8_run
                (id,project_id,organization_id,environment,period_code,version,
                 predecessor_id,energy_binding_id,fleet_selection_id,
                 fuel_pin_id,ir_pin_id,dy_pin_id,input_sha256,
                 state,engine,afec,mpg,be_tco2e,pe_tco2e,le_tco2e,dy,er_tco2e,
                 er_display,trace_json,gate_codes,created_by,temporal_context)
                VALUES (?,?,?,?,?,?,
                  NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, NULLIF(?, '')::uuid,
                  NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, ?,
                  ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, '')::jsonb, string_to_array(?, E'\\n'), ?, ?::jsonb)
                """, id, project, ctx.organization(), ctx.environment(), code, current + 1,
                text(latest == null ? null : latest.id()), text(binding), text(fleet),
                text(loaded.fuelPin()), text(loaded.irPin()), text(loaded.dyPin()),
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
                reviewed ? json(memory.trace()) : "",
                OpenE8RunDecision.encode(decision.gates()), ctx.actor().id(),
                json(loaded.calendar()));
            remember(c, ctx.actor().id(), command, project, id, fingerprint, requestHash);
            Stored stored = first(c, STORED + " WHERE project_id=? AND id=?",
                OpenE8ProjectRuns::stored, project, id);
            return new Outcome(view(c, stored), true);
        });
    }

    private record Loaded(OpenE8RunDecision.OperandSet set, OpenE8Calendar.Context calendar,
                          UUID fuelPin, UUID irPin, UUID dyPin) {}

    /**
     * Reads the project set. A rehearsal needs ED, EC, one EFel, fleet lines,
     * a current fossil reference, a positive declared IR and Dy proved as the
     * absence of fleet credits. Anything else stays incomplete.
     */
    private static Loaded load(Connection c, String project, String period,
            UUID binding, UUID fleet, UUID actor) throws SQLException {
        var gates = new ArrayList<OpenCalculationPreflight.Gate>();
        var calendar = OpenE8Calendar.current(c, project, period, binding);
        if (calendar.projectYear() == null)
            gates.add(OpenE8RunDecision.gate("PROJECT_YEAR", calendar.status()));
        boolean stale = false;
        boolean uniform = false;
        BigDecimal ed = null, ec = null, grid = null;
        if (binding == null) {
            gates.add(OpenE8RunDecision.gate("ED", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EC", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EF_GRID", "MISSING"));
        } else {
            if (OpenE8InputBindings.state(c, project, period, binding).contains("STALE"))
                stale = true;
            Object[] totals = first(c, """
                SELECT sum(ed_kwh), sum(ec_kwh), min(efel_tco2e_per_kwh),
                       count(DISTINCT efel_tco2e_per_kwh)::int
                FROM app_open_e8_input_binding_hour
                WHERE project_id=? AND binding_id=?
                """, r -> new Object[]{r.getBigDecimal(1), r.getBigDecimal(2),
                    r.getBigDecimal(3), r.getInt(4)}, project, binding);
            int factors = totals == null || totals[3] == null ? 0 : (Integer) totals[3];
            uniform = factors == 1;
            if (!uniform)
                gates.add(OpenE8RunDecision.gate("HOURLY_EFEL_NOT_UNIFORM", "REVIEW_REQUIRED"));
            else {
                ed = (BigDecimal) totals[0];
                ec = (BigDecimal) totals[1];
                grid = (BigDecimal) totals[2];
            }
            gates.add(OpenE8RunDecision.gate("ED", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("EC", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("EF_GRID", "NOT_ADMITTED"));
        }
        List<OpenGridCalculationMath.FleetRow> rows = List.of();
        if (fleet == null) {
            gates.add(OpenE8RunDecision.gate("EVR", "MISSING"));
            gates.add(OpenE8RunDecision.gate("AFEC_MPG", "MISSING"));
        } else {
            boolean applicable = fleetApplies(c, project, fleet, binding, calendar.calendarYear());
            boolean fleetStale = fleetMoved(c, project, period, fleet);
            stale |= fleetStale;
            if (applicable && !fleetStale) rows = fleetRows(c, fleet);
            String state = applicable ? "NOT_ADMITTED" : "OUTSIDE_APPLICABLE_WINDOW";
            gates.add(OpenE8RunDecision.gate("EVR", state));
            gates.add(OpenE8RunDecision.gate("AFEC_MPG", state));
        }
        Integer calendarYear = calendar.projectYear() == null ? null : calendar.calendarYear();
        AnnualPin fuel = pin(c, project, "EF_FUEL", calendarYear);
        AnnualPin ir = pin(c, project, "IR", calendarYear);
        AnnualPin dy = pin(c, project, "DY", calendarYear);
        BigDecimal fuelValue = fuelValue(c, fuel);
        BigDecimal irValue = scalar(ir, true);
        OpenGridCalculationMath.Discount discount = discount(dy);
        gates.add(pinGate(c, project, "EF_FUEL", calendarYear, fuel, fuelValue != null));
        gates.add(pinGate(c, project, "IR", calendarYear, ir, irValue != null));
        gates.add(pinGate(c, project, "DY", calendarYear, dy, discount != null));
        gates.add(OpenE8RunDecision.gate("NORMATIVE_PACK", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("CARBON_RIGHTS", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("TECHNICAL_BRANCH", "NOT_ADMITTED"));
        if (fuel != null && fuel.stale() || ir != null && ir.stale() || dy != null && dy.stale())
            stale = true;
        Integer year = calendar.projectYear();
        boolean numbers = uniform && usable(ed, false) && usable(ec, false)
            && usable(grid, false) && fuelValue != null && irValue != null
            && discount != null && !rows.isEmpty() && year != null && !stale;
        OpenGridCalculationMath.Input input = numbers
            ? new OpenGridCalculationMath.Input(OpenGridCalculationMath.BRANCH, year, rows,
                ed, ec, fuelValue, grid, irValue, discount)
            : null;
        boolean author = authored(c, project, binding, fleet, actor)
            || publishedBy(fuel, actor) || publishedBy(ir, actor) || publishedBy(dy, actor);
        return new Loaded(new OpenE8RunDecision.OperandSet(input != null, stale, author,
            List.copyOf(gates), input), calendar,
            fuel == null ? null : fuel.id(), ir == null ? null : ir.id(),
            dy == null ? null : dy.id());
    }

    private Attempt view(Connection c, Stored row) throws SQLException {
        if (row.canIssueVcu())
            throw new IllegalStateException("Run de projeto admite VCU.");
        var calendar = readJson(row.temporalContext(), OpenE8Calendar.Context.class);
        boolean stale = moved(c, row, calendar);
        var gates = new ArrayList<>(decode(row.gates()));
        if (stale) gates.add(OpenE8RunDecision.gate("RUN_REFERENCES", "STALE"));
        OpenE8RunDecision.Memory memory = memory(row);
        return new Attempt(row.id(), row.project(), row.period(), row.version(),
            OpenE8RunDecision.responseState(row.state(), stale), stale, false, row.engine(),
            row.binding(), row.fleet(), row.sha(), List.copyOf(gates),
            OpenCalculationTransparency.plan(gates), memory, row.created(), calendar,
            new ParameterPins(row.fuelPin(), row.irPin(), row.dyPin()));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Falha ao gravar o registro E8.", error);
        }
    }

    private <T> T readJson(String json, Class<T> type) {
        if (json == null) return null;
        try {
            return mapper.readValue(json, type);
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Falha ao ler o registro E8.", error);
        }
    }

    private OpenE8RunDecision.Memory memory(Stored row) {
        if (!"REVIEWED_INTERNAL".equals(row.state()) || row.trace() == null) return null;
        return new OpenE8RunDecision.Memory(row.engine(), "PURE_MATH_TEST_ONLY_NOT_ADMITTED",
            false, row.afec(), row.mpg(), row.be(), row.pe(), row.le(), row.dy(),
            row.er(), row.erDisplay(), List.of(readJson(row.trace(), OpenE8RunDecision.Step[].class)));
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

    private static boolean moved(Connection c, Stored row, OpenE8Calendar.Context calendar)
            throws SQLException {
        UUID bindingNow = latestBinding(c, row.project(), row.period());
        UUID fleetNow = latestFleet(c, row.project(), row.period());
        if (!Objects.equals(row.binding(), bindingNow) || !Objects.equals(row.fleet(), fleetNow))
            return true;
        if (row.binding() != null && OpenE8InputBindings.state(c, row.project(), row.period(),
            row.binding()).contains("STALE")) return true;
        if (row.fleet() != null && fleetMoved(c, row.project(), row.period(), row.fleet()))
            return true;
        // Legacy attempts used the period code as y. Their stored memory is historical only.
        if (calendar == null) return true;
        if (!calendar.equals(OpenE8Calendar.current(c, row.project(), row.period(), row.binding())))
            return true;
        if (calendar.projectYear() == null) return false;
        int year = calendar.calendarYear();
        return pinMoved(c, row.project(), "EF_FUEL", year, row.fuelPin())
            || pinMoved(c, row.project(), "IR", year, row.irPin())
            || pinMoved(c, row.project(), "DY", year, row.dyPin());
    }

    private static boolean pinMoved(Connection c, String project, String kind, int year, UUID stored)
            throws SQLException {
        AnnualPin current = pin(c, project, kind, year);
        UUID latest = current == null ? null : current.id();
        if (!Objects.equals(stored, latest)) return true;
        return current != null && current.stale();
    }

    private static boolean fleetMoved(Connection c, String project, String period, UUID fleet)
            throws SQLException {
        UUID latest = latestFleet(c, project, period);
        if (!fleet.equals(latest)) return true;
        String state = OpenE8FleetSelections.currentState(c, project, period);
        return state != null && state.contains("STALE");
    }

    private static boolean fleetApplies(Connection c, String project, UUID fleet,
                                        UUID binding, Integer year) throws SQLException {
        if (binding == null || year == null) return false;
        return first(c, """
            SELECT EXISTS (SELECT 1 FROM app_open_e8_fleet_selection
                WHERE project_id=? AND id=? AND energy_binding_id=? AND observation_year=?)
            """, r -> r.getBoolean(1), project, fleet, binding, year);
    }

    private static List<OpenGridCalculationMath.FleetRow> fleetRows(Connection c, UUID fleet)
            throws SQLException {
        var rows = query(c, """
            SELECT l.model_id::text, l.evr, l.ev_kwh_per_100mi, l.fossil_mpg_us
            FROM app_open_e8_fleet_selection s
            JOIN app_vehicle_rating_snapshot_line l
              ON l.snapshot_id = s.rating_snapshot_id
             AND l.organization_id = s.organization_id
            WHERE s.id = ?
            ORDER BY l.model_id
            """, r -> new OpenGridCalculationMath.FleetRow(r.getString(1), r.getLong(2),
                r.getBigDecimal(3), r.getBigDecimal(4)), fleet);
        for (var row : rows) {
            if (row.modelCode() == null || row.evr() <= 0
                    || !usable(row.kwhPer100Miles(), true) || !usable(row.comparableMpg(), true))
                return List.of();
        }
        return rows;
    }

    private static AnnualPin pin(Connection c, String project, String kind, Integer year)
            throws SQLException {
        return year == null ? null : OpenProjectParameterBindings.annual(c, project, kind, year);
    }

    private static BigDecimal fuelValue(Connection c, AnnualPin fuel) throws SQLException {
        if (fuel == null || fuel.stale() || !"REFERENCE".equals(fuel.valueKind())) return null;
        BigDecimal value = first(c, """
            SELECT p.normalized_value
            FROM app_open_project_parameter_binding b
            JOIN app_open_parameter_candidate p ON p.id = b.candidate_id
            WHERE b.id=? AND p.parameter_kind='EF_FUEL' AND p.status='CANDIDATE'
              AND p.period_year=b.vintage_year AND p.source_sha256=b.source_sha256
              AND p.methodology='VM0038' AND p.methodology_edition='1.1'
              AND p.scenario='OPEN_LDV_DCFC_SIN_BR' AND p.geography='BR'
              AND p.gas='CO2e' AND p.fuel='GASOLINE'
              AND p.purged_at IS NULL AND p.normalized_unit='tCO2e/USgal'
              AND p.normalized_value IS NOT NULL
            """, r -> r.getBigDecimal(1), fuel.id());
        return usable(value, false) ? value : null;
    }

    private static BigDecimal scalar(AnnualPin pin, boolean positive) {
        if (pin == null || pin.stale() || pin.declared() == null) return null;
        if (!"DECLARED_EXPLICIT".equals(pin.valueKind()) && !"DECLARED_ZERO".equals(pin.valueKind()))
            return null;
        return usable(pin.declared(), positive) ? pin.declared() : null;
    }

    /** Dy enters the rehearsal only as an explicit 1 backed by a stated absence of fleet credits. */
    private static OpenGridCalculationMath.Discount discount(AnnualPin dy) {
        if (dy == null || dy.stale() || dy.declared() == null) return null;
        if (!"DECLARED_EXPLICIT".equals(dy.valueKind())) return null;
        if (dy.rule() == null || dy.evidence() == null) return null;
        if (dy.declared().compareTo(BigDecimal.ONE) != 0) return null;
        return new OpenGridCalculationMath.Discount(BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    private static OpenCalculationPreflight.Gate pinGate(Connection c, String project, String kind,
            Integer year, AnnualPin pin, boolean usable) throws SQLException {
        String state = "MISSING";
        if (pin != null) state = usable ? "NOT_ADMITTED" : "REVIEW_REQUIRED";
        else if (year != null && OpenProjectParameterBindings.hasOtherYear(c, project, kind, year))
            state = "OUTSIDE_APPLICABLE_WINDOW";
        return OpenE8RunDecision.gate(kind, state);
    }

    private static boolean publishedBy(AnnualPin pin, UUID actor) {
        return pin != null && actor.equals(pin.author());
    }

    private static boolean usable(BigDecimal value, boolean positive) {
        if (value == null) return false;
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.precision() > 30 || normalized.scale() > 12
                || normalized.precision() - normalized.scale() > 18) return false;
        return !positive || normalized.signum() > 0;
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
            String period, UUID binding, UUID fleet, byte[] requestHash) throws SQLException {
        Object[] old = first(c, """
            SELECT project_id, run_id, request_sha256
            FROM app_open_e8_run_command
            WHERE actor_id=? AND command_key=?
            """, r -> new Object[]{r.getString(1), r.getObject(2, UUID.class), r.getBytes(3)},
            actor, key);
        if (old == null) return null;
        if (!project.equals(old[0]) || old[2] != null
                && !MessageDigestEquals(requestHash, (byte[]) old[2]))
            throw conflict("A chave de idempotência foi usada com outro conjunto.");
        Stored row = first(c, STORED + " WHERE project_id=? AND id=?",
            OpenE8ProjectRuns::stored, project, (UUID) old[1]);
        if (row == null) throw unavailable();
        if (!period.equals(row.period()) || old[2] == null
                && (binding != null && !binding.equals(row.binding())
                    || fleet != null && !fleet.equals(row.fleet())))
            throw conflict("A chave de idempotência foi usada com outro conjunto.");
        return row;
    }

    private static void remember(Connection c, UUID actor, String key, String project,
                                 UUID run, byte[] fingerprint, byte[] requestHash) throws SQLException {
        update(c, """
            INSERT INTO app_open_e8_run_command
            (actor_id,command_key,project_id,run_id,fingerprint,request_sha256)
            VALUES (?,?,?,?,?,?)
            """, actor, key, project, run, fingerprint, requestHash);
    }

    Attempt newest(Connection c, String project) throws SQLException {
        String period = first(c, """
            SELECT period_code FROM app_open_e8_run
            WHERE project_id=? ORDER BY created_at DESC, version DESC LIMIT 1
            """, r -> r.getString(1), project);
        if (period == null) return null;
        Stored row = latest(c, project, period);
        return row == null ? null : view(c, row);
    }

    private static Stored latest(Connection c, String project, String period) throws SQLException {
        return first(c, STORED + """
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """, OpenE8ProjectRuns::stored, project, period);
    }

    private static String material(String project, String period, UUID binding, UUID fleet,
                                   Loaded loaded) {
        return "E8-RUN-SET-V3|" + project + "|" + period + "|"
            + id(binding) + "|" + id(fleet) + "|"
            + id(loaded.fuelPin()) + "|" + id(loaded.irPin()) + "|" + id(loaded.dyPin())
            + "|" + (loaded.set().actorIsAuthor() ? "AUTHOR" : "OTHER")
            + "|" + loaded.calendar() + "|" + loaded.set().stale()
            + "|" + OpenE8RunDecision.encode(loaded.set().gates());
    }

    private static String id(UUID value) { return value == null ? "-" : value.toString(); }

    private static String text(UUID id) { return id == null ? "" : id.toString(); }

    private static boolean MessageDigestEquals(String hex, byte[] fingerprint) {
        return java.security.MessageDigest.isEqual(HexFormat.of().parseHex(hex), fingerprint);
    }

    private static boolean MessageDigestEquals(byte[] left, byte[] right) {
        return java.security.MessageDigest.isEqual(left, right);
    }

    private static final String STORED = """
        SELECT id,project_id,period_code,version,predecessor_id,energy_binding_id,
          fleet_selection_id,fuel_pin_id,ir_pin_id,dy_pin_id,encode(input_sha256,'hex'),
          state,can_issue_vcu,engine,
          afec,mpg,be_tco2e,pe_tco2e,le_tco2e,dy,er_tco2e,er_display,trace_json::text,
          array_to_string(gate_codes, E'\\n'),created_at,temporal_context::text
        FROM app_open_e8_run
        """;

    private static Stored stored(java.sql.ResultSet r) throws SQLException {
        return new Stored(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
            r.getInt(4), r.getObject(5, UUID.class), r.getObject(6, UUID.class),
            r.getObject(7, UUID.class), r.getObject(8, UUID.class), r.getObject(9, UUID.class),
            r.getObject(10, UUID.class), r.getString(11), r.getString(12), r.getBoolean(13),
            r.getString(14), r.getString(15), r.getString(16), r.getString(17),
            r.getString(18), r.getString(19), r.getString(20), r.getString(21),
            r.getString(22), r.getString(23), r.getString(24),
            r.getObject(25, OffsetDateTime.class), r.getString(26));
    }

    private static PilotProblem unavailable() {
        return new PilotProblem(404, "E8_RUN_UNAVAILABLE", "run", RULE, "Tentativa indisponível.");
    }

    private static PilotProblem conflict(String reason) {
        return new PilotProblem(409, "E8_RUN_CONFLICT", "run", RULE, reason);
    }
}
