package br.carbonflow.pilot;

import static br.carbonflow.pilot.OpenMonitoringModel.*;
import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Preparatory charger and meter configuration for dynamic OPEN projects only.
 * Never uses the SIM pilot's raw facts or calculates E8 from declarations.
 */
@ApplicationScoped
class OpenMonitoringConfiguration {
    private static final String RULE = "CF-OPEN-009";
    private static final Set<String> KINDS = Set.of("DCFC","AC","UNKNOWN");
    private static final Set<String> GRIDS = Set.of("SIN","OTHER","UNKNOWN");
    private static final Set<String> TOPOLOGIES = Set.of("GRID_ONLY","ON_SITE","STORAGE","V2G","UNKNOWN");
    private static final Set<String> ZONES = Set.of("America/Sao_Paulo","UTC","UNKNOWN");
    private final DynamicProjectScope scopes;
    private final ObjectMapper mapper;

    private record Receipt(String action, String target, byte[] fingerprint, String result) {}

    OpenMonitoringConfiguration(DynamicProjectScope scopes, ObjectMapper mapper) {
        this.scopes = scopes;
        this.mapper = mapper;
    }

    View get(String cookie, String project, OffsetDateTime periodFrom, OffsetDateTime periodUntil) {
        return scopes.read(cookie, project, "VIEW_OPEN_MONITORING", (c, context) ->
            view(c, context, periodFrom, periodUntil));
    }

    Charger saveCharger(String cookie, String project, String code, String ifMatch,
                        String keyHeader, ChargerInput raw) {
        return scopes.write(cookie, project, "SAVE_OPEN_MONITORING", (c, scope) -> {
            String id = code(code);
            ChargerInput data = normalize(raw);
            String key = PilotCommands.key(keyHeader);
            int expected = PilotCommands.expected(ifMatch);
            byte[] digest = AccessSecrets.digest("CHARGER|"+project+"|"+id+"|"+expected+"|"+json(data));
            Receipt previous = receipt(c,scope.actor().id(),key);
            if (previous != null) return replay(previous,"SAVE_CHARGER",id,digest,Charger.class);

            Charger old = charger(c,project,id);
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if (expected >= 100000000) throw invalid("version");
            update(c, """
                INSERT INTO app_open_charger_revision
                  (project_id,organization_id,environment,charger_code,version,site_code,
                   charger_kind,rated_power_kw,grid,topology,time_zone,source_reference,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,scope.id(),scope.organization(),scope.environment(),id,expected+1,
                data.siteCode(),data.chargerKind(),data.ratedPowerKw(),data.grid(),
                data.topology(),data.timeZone(),data.sourceReference(),scope.actor().id());
            Charger result = charger(c,project,id);
            store(c,scope.actor().id(),key,project,"SAVE_CHARGER",id,digest,result);
            return result;
        });
    }

    Meter saveMeter(String cookie, String project, String code, String ifMatch,
                    String keyHeader, MeterInput raw) {
        return scopes.write(cookie, project, "SAVE_OPEN_MONITORING", (c,scope) -> {
            String id = code(code);
            MeterInput data = normalize(raw);
            String key = PilotCommands.key(keyHeader);
            int expected = PilotCommands.expected(ifMatch);
            byte[] digest = AccessSecrets.digest("METER|"+project+"|"+id+"|"+expected+"|"+json(data));
            Receipt previous = receipt(c,scope.actor().id(),key);
            if (previous != null) return replay(previous,"SAVE_METER",id,digest,Meter.class);

            Charger charger = charger(c,project,data.chargerCode());
            if (charger == null) {
                throw new PilotProblem(409,"CARREGADOR_PENDENTE","chargerCode",RULE,
                    "Cadastre o carregador antes de associar medidores.");
            }
            Meter old = meter(c,project,id);
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if (expected >= 100000000) throw invalid("version");
            update(c, """
                INSERT INTO app_open_meter_configuration_revision
                  (project_id,organization_id,environment,meter_code,version,
                   charger_code,charger_version,energy_kind,meter_position,source_system,
                   calibration_evidence_ref,calibration_valid_from,calibration_valid_until,
                   configured_from,configured_until,time_zone,recorded_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,scope.id(),scope.organization(),scope.environment(),id,expected+1,
                data.chargerCode(),charger.version(),data.energyKind(),data.meterPosition(),
                data.sourceSystem(),data.calibrationEvidenceRef(),data.calibrationValidFrom(),
                data.calibrationValidUntil(),data.configuredFrom(),data.configuredUntil(),
                data.timeZone(),scope.actor().id());
            Meter result = meter(c,project,id);
            store(c,scope.actor().id(),key,project,"SAVE_METER",id,digest,result);
            return result;
        });
    }

    private static View view(Connection c, DynamicProjectScope.Context scope,
                             OffsetDateTime from, OffsetDateTime until) throws SQLException {
        List<Charger> chargers = query(c, """
                SELECT DISTINCT ON (charger_code) charger_code,version,site_code,charger_kind,
                  rated_power_kw,grid,topology,time_zone,source_reference,recorded_by,recorded_at
                FROM app_open_charger_revision WHERE project_id=?
                ORDER BY charger_code,version DESC
                """,OpenMonitoringConfiguration::mapCharger,scope.id());
        List<Meter> meters = query(c, """
                SELECT DISTINCT ON (meter_code) meter_code,version,charger_code,charger_version,
                  energy_kind,meter_position,source_system,calibration_evidence_ref,
                  calibration_valid_from,calibration_valid_until,configured_from,configured_until,
                  time_zone,recorded_by,recorded_at
                FROM app_open_meter_configuration_revision WHERE project_id=?
                ORDER BY meter_code,version DESC
                """,OpenMonitoringConfiguration::mapMeter,scope.id());
        return new View(scope.id(),scope.environment(),List.copyOf(chargers),List.copyOf(meters),
            OpenMonitoringChecks.evaluate(chargers,meters,from,until));
    }

    private static Charger charger(Connection c,String project,String id) throws SQLException {
        return first(c,"""
            SELECT charger_code,version,site_code,charger_kind,rated_power_kw,grid,topology,
              time_zone,source_reference,recorded_by,recorded_at
            FROM app_open_charger_revision WHERE project_id=? AND charger_code=?
            ORDER BY version DESC LIMIT 1
            """,OpenMonitoringConfiguration::mapCharger,project,id);
    }

    private static Meter meter(Connection c,String project,String id) throws SQLException {
        return first(c,"""
            SELECT meter_code,version,charger_code,charger_version,energy_kind,meter_position,
              source_system,calibration_evidence_ref,calibration_valid_from,calibration_valid_until,
              configured_from,configured_until,time_zone,recorded_by,recorded_at
            FROM app_open_meter_configuration_revision WHERE project_id=? AND meter_code=?
            ORDER BY version DESC LIMIT 1
            """,OpenMonitoringConfiguration::mapMeter,project,id);
    }

    private static Charger mapCharger(java.sql.ResultSet r) throws SQLException {
        return new Charger(r.getString("charger_code"),r.getInt("version"),
            new ChargerInput(r.getString("site_code"),r.getString("charger_kind"),
                r.getBigDecimal("rated_power_kw"),r.getString("grid"),r.getString("topology"),
                r.getString("time_zone"),r.getString("source_reference")),
            r.getObject("recorded_by",UUID.class),r.getObject("recorded_at",OffsetDateTime.class));
    }

    private static Meter mapMeter(java.sql.ResultSet r) throws SQLException {
        return new Meter(r.getString("meter_code"),r.getInt("version"),r.getInt("charger_version"),
            new MeterInput(r.getString("charger_code"),r.getString("energy_kind"),
                r.getString("meter_position"),r.getString("source_system"),
                r.getString("calibration_evidence_ref"),
                r.getObject("calibration_valid_from",OffsetDateTime.class),
                r.getObject("calibration_valid_until",OffsetDateTime.class),
                r.getObject("configured_from",OffsetDateTime.class),
                r.getObject("configured_until",OffsetDateTime.class),r.getString("time_zone")),
            r.getObject("recorded_by",UUID.class),r.getObject("recorded_at",OffsetDateTime.class));
    }

    private static Receipt receipt(Connection c,UUID actor,String key) throws SQLException {
        return first(c, """
            SELECT action,target_code,fingerprint,result::text AS result
            FROM app_open_monitoring_command WHERE actor_id=? AND command_key=?
            """,r->new Receipt(r.getString(1),r.getString(2),r.getBytes(3),r.getString(4)),
            actor,key);
    }

    private <T> T replay(Receipt previous,String action,String target,byte[] fingerprint,
                         Class<T> type) {
        if (!previous.action().equals(action) || !previous.target().equals(target) ||
                !MessageDigest.isEqual(previous.fingerprint(),fingerprint)) {
            throw new PilotProblem(409,"CHAVE_REUSADA","Idempotency-Key",RULE,
                "A chave já identifica outro comando de monitoramento.");
        }
        try {
            return mapper.readValue(previous.result(),type);
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("Comprovante de monitoramento ilegível",problem);
        }
    }

    private void store(Connection c,UUID actor,String key,String project,
                       String action,String target,byte[] digest,Object result) throws SQLException {
        update(c,"""
            INSERT INTO app_open_monitoring_command
              (actor_id,command_key,project_id,action,target_code,fingerprint,result)
            VALUES (?,?,?,?,?,?,?::jsonb)
            """,actor,key,project,action,target,digest,json(result));
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("Falha ao serializar revisão de monitoramento",problem);
        }
    }

    static ChargerInput normalize(ChargerInput value) {
        if (value==null) throw invalid("body");
        String site=code(value.siteCode()),kind=selected(value.chargerKind(),KINDS,"chargerKind");
        String grid=selected(value.grid(),GRIDS,"grid");
        String topology=selected(value.topology(),TOPOLOGIES,"topology");
        String zone=selected(value.timeZone(),ZONES,"timeZone");
        BigDecimal power=value.ratedPowerKw();
        if (power!=null && (power.signum()<=0 || power.compareTo(new BigDecimal("10000"))>0 ||
                power.scale()>3 || power.precision()-power.scale()>9)) throw invalid("ratedPowerKw");
        return new ChargerInput(site,kind,power,grid,topology,zone,text(value.sourceReference(),300));
    }

    static MeterInput normalize(MeterInput value) {
        if (value==null)throw invalid("body");
        String kind=selected(value.energyKind(),Set.of("ED","EC"),"energyKind");
        String expected="ED".equals(kind)?"DOWNSTREAM":"UPSTREAM";
        if (!expected.equals(value.meterPosition()))throw invalid("meterPosition");
        String zone=selected(value.timeZone(),ZONES,"timeZone");
        validateDates(value.calibrationValidFrom(),value.calibrationValidUntil(),
            zone,"calibrationValidity");
        validateDates(value.configuredFrom(),value.configuredUntil(),zone,"configurationValidity");
        String calibration=text(value.calibrationEvidenceRef(),300);
        if (calibration==null &&
                (value.calibrationValidFrom()!=null || value.calibrationValidUntil()!=null))
            throw invalid("calibrationEvidenceRef");
        return new MeterInput(code(value.chargerCode()),kind,expected,
            text(value.sourceSystem(),200),calibration,
            value.calibrationValidFrom(),value.calibrationValidUntil(),
            value.configuredFrom(),value.configuredUntil(),zone);
    }

    private static void validateDates(OffsetDateTime start,OffsetDateTime end,
                                       String zone,String field) {
        if (start==null && end==null)return;
        if (start==null || end==null || !start.toInstant().isBefore(end.toInstant()) ||
                !offsetAllowed(start,zone) || !offsetAllowed(end,zone))throw invalid(field);
    }

    private static boolean offsetAllowed(OffsetDateTime date,String zone) {
        if ("UNKNOWN".equals(zone)) return false;
        return ZoneOffset.UTC.equals(date.getOffset()) ||
            ZoneId.of(zone).getRules().getOffset(date.toInstant()).equals(date.getOffset());
    }

    static String code(String value) {
        if (value==null || !value.matches("[A-Za-z0-9][A-Za-z0-9._-]{0,79}") ||
                value.toUpperCase(java.util.Locale.ROOT).startsWith("SIM-") ||
                value.toUpperCase(java.util.Locale.ROOT).startsWith("PIL-SYN"))throw invalid("code");
        return value;
    }

    private static String selected(String input,Set<String> accepted,String field) {
        if (input==null || !accepted.contains(input)) throw invalid(field);
        return input;
    }

    private static String text(String value,int max) {
        if(value==null || value.isBlank())return null;
        String s=value.strip();
        if (s.codePointCount(0,s.length())>max || s.codePoints().anyMatch(Character::isISOControl)
                || s.toUpperCase(java.util.Locale.ROOT).startsWith("SIM-")
                || s.toUpperCase(java.util.Locale.ROOT).startsWith("PIL-SYN"))throw invalid("reference");
        return s;
    }

    private static PilotProblem invalid(String field) {
        return PilotProblem.invalid("MONITORING_INVALIDO",field,RULE,
            "Revise identificação, período, topologia, unidade e evidência do medidor.");
    }
}
