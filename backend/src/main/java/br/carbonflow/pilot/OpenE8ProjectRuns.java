package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

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

import br.carbonflow.pilot.OpenProjectParameterBindings.ExecutionPin;

/**
 * Records an immutable calculation attempt for one dynamic project period.
 * The HTTP body carries version identifiers only. The kernel runs only when
 * the stored hourly ED, EC and EFel, fleet lines, fossil pin, IR and Dy form one
 * set and the actor did not author those links. The result stays a rehearsal:
 * usableForE8 and canIssueVcu remain false.
 */
@ApplicationScoped
class OpenE8ProjectRuns {
    private static final String RULE = "CF-OPEN-008";

    record Request(UUID energyBindingId, UUID fleetSelectionId) {}

    record Timing(OffsetDateTime windowStart, OffsetDateTime windowEndExclusive,
                  Integer projectYear, Integer sourceYear) {}

    record Attempt(UUID id, String projectId, String period, int version,
                   String state, boolean stale, boolean canIssueVcu, String engine,
                   UUID energyBindingId, UUID fleetSelectionId, String inputSha256,
                   List<OpenCalculationPreflight.Gate> gates,
                   List<OpenCalculationTransparency.Stage> equationPlan,
                   OpenE8RunDecision.Memory memory, OffsetDateTime createdAt, Timing timing) {}

    record InputReference(String code,String basis,UUID id,Integer version,String sha256,String state) {}
    record Selection(String policyVersion,String projectId,String period,Timing timing,String timingGate,
                     List<InputReference> inputs,boolean usableForE8,boolean canIssueVcu) {}
    record Listing(String projectId, String period, boolean canRecord, List<Attempt> runs,
                   Selection currentSelection) {}

    record Outcome(Attempt view, boolean created) {}

    private record Stored(UUID id, String project, String period, int version,
                          UUID predecessor, UUID binding, UUID fleet,
                          UUID fuelPin, UUID irPin, UUID dyPin, String sha,
                          String state, boolean canIssueVcu, String engine,
                          String afec, String mpg, String be, String pe, String le,
                          String dy, String er, String erDisplay, String trace,
                          String gates, OffsetDateTime created, String timingSha, Timing timing) {}

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
                List.copyOf(runs), selection(c,project,code));
        });
    }

    /**
     * Current default selection, not a snapshot of any stored attempt.
     * Reuses the executor's period and source-year selectors; exposes metadata only.
     */
    private static Selection selection(Connection c,String project,String period) throws SQLException {
        UUID binding=latestBinding(c,project,period),fleet=latestFleet(c,project,period);
        var context=OpenE8Timing.resolve(c,project,binding);
        var window=context.window();
        Integer year=context.year().sourceYear();
        var timing=new Timing(window==null?null:window.from(),window==null?null:window.until(),
            context.year().projectYear(),year);
        var inputs=new ArrayList<InputReference>();
        if (binding==null) {
            inputs.add(new InputReference("ED_EC_EFEL","HOURLY_PERIOD_BINDING",null,null,null,"MISSING"));
        } else {
            String state=OpenE8InputBindings.state(c,project,period,binding);
            String result=state.contains("STALE")?"STALE":year==null?"TIMING_UNRESOLVED":
                "CANDIDATE_NOT_ADMITTED".equals(state)?"NOT_ADMITTED":"REVIEW_REQUIRED";
            inputs.add(first(c,"""
                SELECT id,version,encode(input_sha256,'hex') FROM app_open_e8_input_binding_candidate
                WHERE project_id=? AND period_code=? AND id=?
                """,r->new InputReference("ED_EC_EFEL","HOURLY_PERIOD_BINDING",r.getObject(1,UUID.class),
                    r.getInt(2),r.getString(3),result),project,period,binding));
        }
        if (fleet==null) {
            inputs.add(new InputReference("EVR_RATINGS","FLEET_PERIOD_SELECTION",null,null,null,"MISSING"));
        } else {
            String state=OpenE8FleetSelections.currentState(c,project,period);
            inputs.add(first(c,"""
                SELECT id,version,encode(selection_sha256,'hex'),energy_binding_id,observation_year
                FROM app_open_e8_fleet_selection WHERE project_id=? AND period_code=? AND id=?
                """,r->new InputReference("EVR_RATINGS","FLEET_PERIOD_SELECTION",r.getObject(1,UUID.class),
                    r.getInt(2),r.getString(3),state.contains("STALE")?"STALE":year==null?"TIMING_UNRESOLVED":
                    Objects.equals(binding,r.getObject(4,UUID.class))&&year==r.getInt(5)&&
                    "PREPARATORY_NOT_ADMITTED".equals(state)?"NOT_ADMITTED":"REVIEW_REQUIRED"),project,period,fleet));
        }
        for (String kind:List.of("EF_FUEL","IR","DY")) inputs.add(sourcePinReference(c,project,kind,year));
        return new Selection("OPEN-E8-INPUT-SELECTION@1",project,period,timing,context.year().gate(),
            List.copyOf(inputs),false,false);
    }

    // Source-year pin is the exact same selector as load() and moved().
    static InputReference sourcePinReference(Connection c,String project,String kind,Integer year) throws SQLException {
        String basis="DY".equals(kind)?"LEGACY_SOURCE_YEAR_PIN":"SOURCE_YEAR_PIN";
        if (year==null) return new InputReference(kind,basis,null,null,null,"TIMING_UNRESOLVED");
        var selected=pin(c,project,kind,year);
        if (selected==null) return new InputReference(kind,basis,null,null,null,"MISSING_FOR_SOURCE_YEAR");
        boolean usable=!selected.stale() && switch(kind) {
            case "EF_FUEL" -> fuelValue(c,selected)!=null;
            case "IR" -> scalar(selected,true)!=null;
            case "DY" -> discount(selected)!=null;
            default -> false;
        };
        String state=pinState(selected,usable,year);
        return first(c,"""
            SELECT id,version,encode(binding_sha256,'hex') FROM app_open_project_parameter_binding
            WHERE project_id=? AND parameter_kind=? AND vintage_year=? AND id=?
            """,r->new InputReference(kind,basis,r.getObject(1,UUID.class),r.getInt(2),r.getString(3),state),
            project,kind,year,selected.id());
    }

    /** One current attempt per period; historical revisions must not be summed twice. */
    static List<Attempt> current(Connection c, String project) throws SQLException {
        var rows = query(c, STORED + """
            WHERE project_id=? AND (period_code,version) IN
              (SELECT period_code,max(version) FROM app_open_e8_run
               WHERE project_id=? GROUP BY period_code)
            ORDER BY period_code
            """, OpenE8ProjectRuns::stored, project, project);
        var result = new ArrayList<Attempt>();
        for (var row : rows) result.add(view(c, row));
        return List.copyOf(result);
    }

    Attempt detail(String cookie, String project, String period, UUID id) {
        return scope.read(cookie, project, "VIEW_OPEN_E8_RUN", (c, ctx) -> {
            return find(c, project, period, id);
        });
    }

    /** Caller must already hold VIEW_OPEN_E8_RUN for this project. */
    static Attempt find(Connection c, String project, String period, UUID id) throws SQLException {
        String code = OpenMonitoringDossiers.code(period);
        Stored row = first(c, STORED + """
            WHERE project_id=? AND period_code=? AND id=?
            """, OpenE8ProjectRuns::stored, project, code, id);
        if (row == null) throw unavailable();
        return view(c, row);
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
            Stored replay = commandRun(c, ctx.actor().id(), command, project, code,
                requestedBinding, requestedFleet);
            if (replay != null) return new Outcome(view(c, replay), false);

            UUID binding = resolveBinding(c, project, code, requestedBinding);
            UUID fleet = resolveFleet(c, project, code, requestedFleet);
            Loaded loaded = load(c, project, code, binding, fleet, ctx.actor().id());
            byte[] fingerprint = AccessSecrets.digest(material(project, code, binding, fleet, loaded));

            Stored latest = latest(c, project, code);
            boolean sameSet = latest != null && matchesFingerprint(latest.sha(), fingerprint);
            if (sameSet) {
                PilotCommands.requireVersion(expected, latest.version());
                remember(c, ctx.actor().id(), command, project, latest.id(), fingerprint);
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
                 er_display,trace_json,gate_codes,created_by)
                VALUES (?,?,?,?,?,?,
                  NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, NULLIF(?, '')::uuid,
                  NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, NULLIF(?, '')::uuid, ?,
                  ?, NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''), NULLIF(?, ''),
                  NULLIF(?, ''), NULLIF(?, '')::jsonb, string_to_array(?, E'\\n'), ?)
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
                reviewed ? OpenE8RunDecision.traceJson(memory) : "",
                OpenE8RunDecision.encode(decision.gates()), ctx.actor().id());
            OpenE8Timing.Context timing = loaded.timing();
            OpenE8Timing.Window window = timing.window();
            update(c, """
                INSERT INTO app_open_e8_run_timing
                  (project_id,id,timing_sha256,window_start,window_end_exclusive,project_year,source_year)
                VALUES (?,?,?,?,?,?,?)
                """, project, id, HexFormat.of().parseHex(timing.fingerprint()),
                window == null ? null : window.from(), window == null ? null : window.until(),
                timing.year().projectYear(), timing.year().sourceYear());
            remember(c, ctx.actor().id(), command, project, id, fingerprint);
            Stored stored = first(c, STORED + " WHERE project_id=? AND id=?",
                OpenE8ProjectRuns::stored, project, id);
            return new Outcome(view(c, stored), true);
        });
    }

    private record Loaded(OpenE8RunDecision.OperandSet set, UUID fuelPin, UUID irPin, UUID dyPin,
                          OpenE8Timing.Context timing) {}

    /**
     * Reads the project set. A rehearsal needs paired hourly ED/EC/EFel, fleet lines,
     * a current fossil reference, a positive declared IR and Dy proved as the
     * absence of fleet credits. Anything else stays incomplete.
     */
    private static Loaded load(Connection c, String project, String period,
            UUID binding, UUID fleet, UUID actor) throws SQLException {
        var gates = new ArrayList<OpenCalculationPreflight.Gate>();
        OpenE8Timing.Context timing = OpenE8Timing.resolve(c, project, binding);
        if (timing.year().gate() != null) {
            gates.add(OpenE8RunDecision.gate(timing.year().gate(), "REVIEW_REQUIRED"));
        }
        Integer sourceYear = timing.year().sourceYear();
        boolean stale = false;
        OpenHourlyProjectEmissions.Series hourly = null;
        OpenHourlyProjectEmissions.Memory energy = null;
        BigDecimal ed = null, ec = null;
        if (binding == null) {
            gates.add(OpenE8RunDecision.gate("ED", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EC", "MISSING"));
            gates.add(OpenE8RunDecision.gate("EF_GRID", "MISSING"));
        } else {
            if (OpenE8InputBindings.state(c, project, period, binding).contains("STALE"))
                stale = true;
            try {
                hourly = hourlyEnergy(c, project, binding, timing.window());
                energy = OpenHourlyProjectEmissions.calculate(hourly);
                ed = new BigDecimal(energy.edKwh());
                ec = new BigDecimal(energy.ecKwh());
            } catch (IllegalArgumentException invalid) {
                gates.add(OpenE8RunDecision.gate("HOURLY_COVERAGE_INVALID", "REVIEW_REQUIRED"));
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
            if (fleetMoved(c, project, period, fleet)) stale = true;
            if (sourceYear != null) {
                boolean compatible = first(c, """
                    SELECT EXISTS (SELECT 1 FROM app_open_e8_fleet_selection
                    WHERE project_id=? AND id=? AND energy_binding_id=? AND observation_year=?)
                    """, r -> r.getBoolean(1), project, fleet, binding, sourceYear);
                if (compatible) {
                    rows = fleetRows(c, fleet);
                } else {
                    gates.add(OpenE8RunDecision.gate("FLEET_WINDOW_MISMATCH", "REVIEW_REQUIRED"));
                }
            }
            gates.add(OpenE8RunDecision.gate("EVR", "NOT_ADMITTED"));
            gates.add(OpenE8RunDecision.gate("AFEC_MPG", "NOT_ADMITTED"));
        }
        ExecutionPin fuel = pin(c, project, "EF_FUEL", sourceYear);
        ExecutionPin ir = pin(c, project, "IR", sourceYear);
        ExecutionPin dy = pin(c, project, "DY", sourceYear);
        BigDecimal fuelValue = fuelValue(c, fuel);
        BigDecimal irValue = scalar(ir, true);
        OpenGridCalculationMath.Discount discount = discount(dy);
        gates.add(OpenE8RunDecision.gate("EF_FUEL", pinState(fuel, fuelValue != null, sourceYear)));
        gates.add(OpenE8RunDecision.gate("IR", pinState(ir, irValue != null, sourceYear)));
        gates.add(OpenE8RunDecision.gate("DY", pinState(dy, discount != null, sourceYear)));
        gates.add(OpenE8RunDecision.gate("NORMATIVE_PACK", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("CARBON_RIGHTS", "NOT_ADMITTED"));
        gates.add(OpenE8RunDecision.gate("TECHNICAL_BRANCH", "NOT_ADMITTED"));
        if (fuel != null && fuel.stale() || ir != null && ir.stale() || dy != null && dy.stale())
            stale = true;
        Integer year = timing.year().projectYear();
        boolean numbers = energy != null && usable(ed, false) && usable(ec, false)
            && fuelValue != null && irValue != null
            && discount != null && !rows.isEmpty() && year != null && !stale;
        OpenGridCalculationMath.Input input = numbers
            ? new OpenGridCalculationMath.Input(OpenGridCalculationMath.BRANCH, year, rows,
                ed, ec, fuelValue, null, irValue, discount)
            : null;
        boolean author = authored(c, project, binding, fleet, actor)
            || publishedBy(fuel, actor) || publishedBy(ir, actor) || publishedBy(dy, actor);
        return new Loaded(new OpenE8RunDecision.OperandSet(input != null, stale, author,
            List.copyOf(gates), input, hourly),
            fuel == null ? null : fuel.id(), ir == null ? null : ir.id(),
            dy == null ? null : dy.id(), timing);
    }

    private static Attempt view(Connection c, Stored row) throws SQLException {
        if (row.canIssueVcu())
            throw new IllegalStateException("Run de projeto admite VCU.");
        boolean stale = moved(c, row);
        var gates = new ArrayList<>(decode(row.gates()));
        if (row.timingSha() == null) {
            gates.add(OpenE8RunDecision.gate("RUN_TIMING_LEGACY", "STALE"));
        } else if (stale) {
            gates.add(OpenE8RunDecision.gate("RUN_REFERENCES", "STALE"));
        }
        OpenE8RunDecision.Memory memory = memory(row);
        return new Attempt(row.id(), row.project(), row.period(), row.version(),
            OpenE8RunDecision.responseState(row.state(), stale), stale, false, row.engine(),
            row.binding(), row.fleet(), row.sha(), List.copyOf(gates),
            OpenCalculationTransparency.plan(gates), memory, row.created(), row.timing());
    }

    private static OpenE8RunDecision.Memory memory(Stored row) {
        if (!"REVIEWED_INTERNAL".equals(row.state()) || row.trace() == null) return null;
        var trace = OpenE8RunDecision.readTrace(row.trace());
        return new OpenE8RunDecision.Memory(row.engine(), "PURE_MATH_TEST_ONLY_NOT_ADMITTED",
            false, row.afec(), row.mpg(), row.be(), row.pe(), row.le(), row.dy(),
            row.er(), row.erDisplay(), trace.steps(), trace.hourlyPe());
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
        if (row.fleet() != null && fleetMoved(c, row.project(), row.period(), row.fleet()))
            return true;
        if (row.timingSha() == null) return true;
        var timing = OpenE8Timing.resolve(c, row.project(), row.binding());
        if (!row.timingSha().equals(timing.fingerprint())) return true;
        Integer sourceYear = timing.year().sourceYear();
        return pinMoved(c, row.project(), "EF_FUEL", sourceYear, row.fuelPin())
            || pinMoved(c, row.project(), "IR", sourceYear, row.irPin())
            || pinMoved(c, row.project(), "DY", sourceYear, row.dyPin());
    }

    private static boolean pinMoved(Connection c, String project, String kind, Integer sourceYear, UUID stored)
            throws SQLException {
        ExecutionPin current = pin(c, project, kind, sourceYear);
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

    private static ExecutionPin pin(Connection c, String project, String kind, Integer sourceYear)
            throws SQLException {
        return sourceYear == null ? null
            : OpenProjectParameterBindings.forSourceYear(c, project, kind, sourceYear);
    }

    private static BigDecimal fuelValue(Connection c, ExecutionPin fuel) throws SQLException {
        if (fuel == null || fuel.stale() || !"REFERENCE".equals(fuel.valueKind())) return null;
        BigDecimal value = first(c, """
            SELECT p.normalized_value
            FROM app_open_project_parameter_binding b
            JOIN app_open_parameter_candidate p ON p.id = b.candidate_id
            WHERE b.id=? AND p.parameter_kind='EF_FUEL' AND p.status='CANDIDATE'
              AND p.purged_at IS NULL AND p.normalized_unit='tCO2e/USgal'
              AND p.normalized_value IS NOT NULL
            """, r -> r.getBigDecimal(1), fuel.id());
        return usable(value, false) ? value : null;
    }

    private static BigDecimal scalar(ExecutionPin pin, boolean positive) {
        if (pin == null || pin.stale() || pin.declared() == null) return null;
        if (!"DECLARED_EXPLICIT".equals(pin.valueKind()) && !"DECLARED_ZERO".equals(pin.valueKind()))
            return null;
        return usable(pin.declared(), positive) ? pin.declared() : null;
    }

    /** Dy enters the rehearsal only as an explicit 1 backed by a stated absence of fleet credits. */
    private static OpenGridCalculationMath.Discount discount(ExecutionPin dy) {
        if (dy == null || dy.stale() || dy.declared() == null) return null;
        if (!"DECLARED_EXPLICIT".equals(dy.valueKind())) return null;
        if (dy.rule() == null || dy.evidence() == null) return null;
        if (dy.declared().compareTo(BigDecimal.ONE) != 0) return null;
        return new OpenGridCalculationMath.Discount(BigDecimal.ZERO, BigDecimal.ZERO, true);
    }

    private static String pinState(ExecutionPin pin, boolean usable, Integer sourceYear) {
        if (pin == null) return sourceYear == null ? "MISSING" : "MISSING_FOR_SOURCE_YEAR";
        if (pin.stale()) return "STALE";
        if (!usable) return "REVIEW_REQUIRED";
        return "NOT_ADMITTED";
    }

    private static boolean publishedBy(ExecutionPin pin, UUID actor) {
        return pin != null && actor.equals(pin.author());
    }

    private static boolean usable(BigDecimal value, boolean positive) {
        if (value == null) return false;
        BigDecimal normalized = value.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.precision() > 30 || normalized.scale() > 12
                || normalized.precision() - normalized.scale() > 18) return false;
        return !positive || normalized.signum() > 0;
    }

    private record EnergyContext(int hours, BigDecimal ed, BigDecimal ec, String region,
                                 OffsetDateTime from, OffsetDateTime until, String unit, String gas) {}

    private static OpenHourlyProjectEmissions.Series hourlyEnergy(Connection c, String project,
            UUID binding, OpenE8Timing.Window window) throws SQLException {
        EnergyContext context = first(c, """
            SELECT b.hour_count,q.ed_kwh,q.ec_kwh,f.region,f.period_from,f.period_until,
                   f.normalized_unit,f.gas
            FROM app_open_e8_input_binding_candidate b
            JOIN app_open_e8_energy_candidate e ON e.project_id=b.project_id AND e.id=b.energy_candidate_id
            JOIN app_open_period_qa_snapshot q ON q.project_id=e.project_id AND q.id=e.snapshot_id
            JOIN app_open_efel_hourly_candidate f ON f.project_id=b.project_id AND f.id=b.efel_candidate_id
              AND f.energy_candidate_id=e.id
            WHERE b.project_id=? AND b.id=?
            """, r -> new EnergyContext(r.getInt(1), r.getBigDecimal(2), r.getBigDecimal(3),
                r.getString(4), r.getObject(5, OffsetDateTime.class),
                r.getObject(6, OffsetDateTime.class), r.getString(7), r.getString(8)), project, binding);
        if (window == null || context == null || !"tCO2e/kWh".equals(context.unit())
                || !"CO2e".equals(context.gas())
                || !window.from().toInstant().equals(context.from().toInstant())
                || !window.until().toInstant().equals(context.until().toInstant()))
            throw new IllegalArgumentException("Janela ou unidade do fator incompatível.");
        var hours = query(c, """
            SELECT hour_start,ed_kwh,ec_kwh,efel_tco2e_per_kwh
            FROM app_open_e8_input_binding_hour WHERE project_id=? AND binding_id=?
            ORDER BY hour_start
            """, r -> new OpenHourlyProjectEmissions.Hour(
                r.getObject(1, OffsetDateTime.class).toInstant(), r.getBigDecimal(2),
                r.getBigDecimal(3), r.getBigDecimal(4)), project, binding);
        var series = new OpenHourlyProjectEmissions.Series(window.from().toInstant(),
            window.until().toInstant(), context.region(), List.copyOf(hours));
        var memory = OpenHourlyProjectEmissions.calculate(series);
        if (hours.size() != context.hours()
                || context.ed().compareTo(new BigDecimal(memory.edKwh())) != 0
                || context.ec().compareTo(new BigDecimal(memory.ecKwh())) != 0)
            throw new IllegalArgumentException("Cobertura ou totais divergem do snapshot de QA.");
        return series;
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
                                     String period, UUID requestedBinding, UUID requestedFleet)
            throws SQLException {
        String recordedProject = first(c, """
            SELECT project_id FROM app_open_e8_run_command WHERE actor_id=? AND command_key=?
            """, r -> r.getString(1), actor, key);
        if (recordedProject == null) return null;
        if (!project.equals(recordedProject))
            throw conflict("A chave de idempotência foi usada com outro conjunto.");
        Stored row = first(c, STORED + """
            WHERE project_id=? AND id=(SELECT run_id FROM app_open_e8_run_command
                                      WHERE actor_id=? AND command_key=?)
            """, OpenE8ProjectRuns::stored, project, actor, key);
        if (row == null) throw unavailable();
        // Omitted references keep the defaults captured on the original request.
        if (!period.equals(row.period())
                || requestedBinding != null && !requestedBinding.equals(row.binding())
                || requestedFleet != null && !requestedFleet.equals(row.fleet())) {
            throw conflict("A chave de idempotência foi usada com outro conjunto.");
        }
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
        return "E8-RUN-SET-V4-HOURLY|" + project + "|" + period + "|"
            + id(binding) + "|" + id(fleet) + "|"
            + id(loaded.fuelPin()) + "|" + id(loaded.irPin()) + "|" + id(loaded.dyPin())
            + "|" + loaded.timing().fingerprint() + "|" + loaded.set().stale()
            + "|" + (loaded.set().actorIsAuthor() ? "AUTHOR" : "OTHER");
    }

    private static String id(UUID value) { return value == null ? "-" : value.toString(); }

    private static String text(UUID id) { return id == null ? "" : id.toString(); }

    private static boolean matchesFingerprint(String hex, byte[] fingerprint) {
        return java.security.MessageDigest.isEqual(HexFormat.of().parseHex(hex), fingerprint);
    }

    private static final String STORED = """
        SELECT id,project_id,period_code,version,predecessor_id,energy_binding_id,
          fleet_selection_id,fuel_pin_id,ir_pin_id,dy_pin_id,encode(input_sha256,'hex'),
          state,can_issue_vcu,engine,
          afec,mpg,be_tco2e,pe_tco2e,le_tco2e,dy,er_tco2e,er_display,trace_json::text,
          array_to_string(gate_codes, E'\\n'),created_at,
          encode(timing_sha256,'hex'),window_start,window_end_exclusive,project_year,source_year
        FROM app_open_e8_run LEFT JOIN app_open_e8_run_timing USING (project_id,id)
        """;

    private static Stored stored(java.sql.ResultSet r) throws SQLException {
        String timingSha = r.getString(26);
        Timing timing = timingSha == null ? null : new Timing(
            r.getObject(27, OffsetDateTime.class), r.getObject(28, OffsetDateTime.class),
            r.getObject(29, Integer.class), r.getObject(30, Integer.class));
        return new Stored(r.getObject(1, UUID.class), r.getString(2), r.getString(3),
            r.getInt(4), r.getObject(5, UUID.class), r.getObject(6, UUID.class),
            r.getObject(7, UUID.class), r.getObject(8, UUID.class), r.getObject(9, UUID.class),
            r.getObject(10, UUID.class), r.getString(11), r.getString(12), r.getBoolean(13),
            r.getString(14), r.getString(15), r.getString(16), r.getString(17),
            r.getString(18), r.getString(19), r.getString(20), r.getString(21),
            r.getString(22), r.getString(23), r.getString(24),
            r.getObject(25, OffsetDateTime.class), timingSha, timing);
    }

    private static PilotProblem unavailable() {
        return new PilotProblem(404, "E8_RUN_UNAVAILABLE", "run", RULE, "Tentativa indisponível.");
    }

    private static PilotProblem conflict(String reason) {
        return new PilotProblem(409, "E8_RUN_CONFLICT", "run", RULE, reason);
    }
}
