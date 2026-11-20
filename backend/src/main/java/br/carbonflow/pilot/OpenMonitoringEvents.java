package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Append-only operational occurrences affecting ED/EC. An internal REV may
 * independently disposition a report, but never approve energy for E8.
 */
@ApplicationScoped
class OpenMonitoringEvents {
    private static final String RULE="CF-OPEN-009";
    private static final Set<String> KINDS=Set.of(
        "SOURCE_OUTAGE","METER_MAINTENANCE","CLOCK_DRIFT","DATA_GAP");
    private static final Set<String> TYPES=Set.of("SOURCE","METER","CHARGER");
    private final DynamicProjectScope scope;

    record Input(String kind,String subjectType,String subjectCode,
                 OffsetDateTime from,OffsetDateTime until,
                 String incidentReference,String description) {}
    record Decision(String outcome,String reason,String evidenceReference) {}
    record Review(int version,String outcome,String reason,String evidenceReference,
                  UUID reviewedBy,OffsetDateTime reviewedAt) {}
    record Event(UUID id,String projectId,String kind,String subjectType,
                 String subjectCode,OffsetDateTime from,OffsetDateTime until,
                 String incidentReference,String description,UUID submittedBy,
                 OffsetDateTime submittedAt,Review review) {}

    OpenMonitoringEvents(DynamicProjectScope scope) {
        this.scope=scope;
    }

    Event report(String cookie,String project,String key,Input input) {
        return scope.write(cookie,project,"SUBMIT_OPEN_MONITORING_EVENT",(c,ctx)->{
            Input valid=validated(input);
            checkSubject(c,project,valid);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("EVENT|"+project+"|"+valid.kind()+"|"+
                valid.subjectType()+"|"+valid.subjectCode()+"|"+valid.from().toInstant()+"|"+
                valid.until().toInstant()+"|"+valid.incidentReference()+"|"+valid.description());
            Object[] existing=first(c,"""
                SELECT project_id,event_id,fingerprint FROM app_open_operational_event_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getBytes(3)},
                ctx.actor().id(),command);
            if(existing!=null) {
                if(!project.equals(existing[0]) ||
                   !MessageDigest.isEqual(fingerprint,(byte[])existing[2]))
                    throw conflict("A chave de idempotência já identifica outra ocorrência.");
                return event(c,project,(UUID)existing[1]);
            }
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_operational_event
                (id,project_id,organization_id,environment,kind,subject_type,
                 subject_code,period_start,period_end,incident_reference,
                 description,submitted_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),valid.kind(),
                valid.subjectType(),valid.subjectCode(),valid.from(),valid.until(),
                valid.incidentReference(),valid.description(),ctx.actor().id());
            update(c,"""
                INSERT INTO app_open_operational_event_command
                (actor_id,command_key,project_id,fingerprint,event_id)
                VALUES (?,?,?,?,?)
                """,ctx.actor().id(),command,project,fingerprint,id);
            return event(c,project,id);
        });
    }

    Review review(String cookie,String project,UUID eventId,String ifMatch,Decision input) {
        return scope.write(cookie,project,"REVIEW_OPEN_MONITORING_EVENT",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            Event event=event(c,project,eventId);
            if(event.submittedBy().equals(ctx.actor().id()))
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                    "O remetente da ocorrência não pode revisar o próprio relato.");
            if(input==null || input.outcome()==null ||
                !Set.of("CONFIRMED_BLOCKING","CLEARED_FOR_INTERNAL_QA").contains(input.outcome()))
                throw invalid("outcome","Informe uma decisão técnica interna válida.");
            String reason=safeText(input.reason(),15,2000,"reason");
            String evidence=safeText(input.evidenceReference(),8,300,"evidenceReference");
            int expected=PilotCommands.expected(ifMatch);
            Review previous=event.review();
            if(previous!=null && previous.version()==expected+1 &&
                previous.reviewedBy().equals(ctx.actor().id()) &&
                previous.outcome().equals(input.outcome()) &&
                previous.reason().equals(reason) &&
                previous.evidenceReference().equals(evidence))return previous;
            PilotCommands.requireVersion(expected,previous==null?0:previous.version());
            if(expected>=100000000)throw invalid("version","Limite de revisões excedido.");
            update(c,"""
                INSERT INTO app_open_operational_event_review
                (project_id,event_id,version,outcome,reason,evidence_reference,reviewed_by)
                VALUES (?,?,?,?,?,?,?)
                """,project,eventId,expected+1,input.outcome(),reason,evidence,ctx.actor().id());
            return event(c,project,eventId).review();
        });
    }

    List<Event> list(String cookie,String project) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->
            query(c,EVENTS_SQL+"""
                WHERE e.project_id=?
                ORDER BY e.submitted_at DESC,e.id DESC LIMIT 60
                """,r->mapEvent(c,project,r),project));
    }

    /** Returns every incident in the period; resolved incidents also pin SHA lineage. */
    static List<OpenPeriodQa.Incident> impacting(Connection c,String project,
                                                 OffsetDateTime from,OffsetDateTime until)
        throws SQLException {
        return query(c,"""
            SELECT e.id,e.kind,e.subject_type,e.subject_code,
              e.period_start,e.period_end,
              r.version,r.outcome
            FROM app_open_operational_event e
            LEFT JOIN LATERAL (
              SELECT version,outcome FROM app_open_operational_event_review rev
              WHERE rev.project_id=e.project_id AND rev.event_id=e.id
              ORDER BY version DESC LIMIT 1
            ) r ON true
            WHERE e.project_id=? AND e.period_start < ? AND e.period_end > ?
            ORDER BY e.period_start,e.id
            """,r->new OpenPeriodQa.Incident(
                r.getObject("id",UUID.class),r.getString("kind"),
                r.getString("subject_type"),r.getString("subject_code"),
                r.getObject("period_start",OffsetDateTime.class).toInstant(),
                r.getObject("period_end",OffsetDateTime.class).toInstant(),
                r.getObject("version")==null?0:r.getInt("version"),
                r.getString("outcome")),project,until,from);
    }

    private static final String EVENTS_SQL="""
        SELECT e.id,e.project_id,e.kind,e.subject_type,e.subject_code,
          e.period_start,e.period_end,e.incident_reference,e.description,
          e.submitted_by,e.submitted_at
        FROM app_open_operational_event e
        """;

    private static Event event(Connection c,String project,UUID id) throws SQLException {
        Event value=first(c,EVENTS_SQL+"""
            WHERE e.project_id=? AND e.id=?
            """,r->mapEvent(c,project,r),project,id);
        if(value==null)throw unavailable();
        return value;
    }
    private static Event mapEvent(Connection c,String project,java.sql.ResultSet r)
        throws SQLException {
        UUID id=r.getObject("id",UUID.class);
        Review review=first(c,"""
            SELECT version,outcome,reason,evidence_reference,reviewed_by,reviewed_at
            FROM app_open_operational_event_review
            WHERE project_id=? AND event_id=? ORDER BY version DESC LIMIT 1
            """,row->new Review(row.getInt("version"),row.getString("outcome"),
                row.getString("reason"),row.getString("evidence_reference"),
                row.getObject("reviewed_by",UUID.class),
                row.getObject("reviewed_at",OffsetDateTime.class)),project,id);
        return new Event(id,r.getString("project_id"),r.getString("kind"),
            r.getString("subject_type"),r.getString("subject_code"),
            r.getObject("period_start",OffsetDateTime.class),
            r.getObject("period_end",OffsetDateTime.class),
            r.getString("incident_reference"),r.getString("description"),
            r.getObject("submitted_by",UUID.class),
            r.getObject("submitted_at",OffsetDateTime.class),review);
    }

    private static Input validated(Input input) {
        if(input==null || !KINDS.contains(input.kind()) ||
           !TYPES.contains(input.subjectType()))throw invalid("kind","Tipo de ocorrência desconhecido.");
        boolean validPair=switch(input.kind()) {
            case "SOURCE_OUTAGE" -> "SOURCE".equals(input.subjectType());
            case "METER_MAINTENANCE","CLOCK_DRIFT" -> "METER".equals(input.subjectType());
            case "DATA_GAP" -> true;
            default -> false;
        };
        if(!validPair)throw invalid("subjectType","Informe a fronteira correta da ocorrência.");
        String subject=OpenMonitoringConfiguration.code(input.subjectCode());
        if(input.from()==null || input.until()==null ||
           !input.from().toInstant().isBefore(input.until().toInstant()) ||
           Duration.between(input.from().toInstant(),input.until().toInstant())
              .compareTo(Duration.ofDays(31))>0)
            throw invalid("period","O evento precisa de datas com fuso e intervalo de até 31 dias.");
        return new Input(input.kind(),input.subjectType(),subject,
            input.from(),input.until(),
            safeText(input.incidentReference(),8,300,"incidentReference"),
            safeText(input.description(),15,2000,"description"));
    }
    private static String safeText(String value,int min,int max,String field) {
        if(value==null || value.strip().length()<min || value.strip().length()>max ||
           value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field,"Informe texto verificável sem caracteres de controle.");
        return value.strip();
    }
    private static void checkSubject(Connection c,String project,Input event) throws SQLException {
        Boolean found=switch(event.subjectType()) {
            case "SOURCE" -> first(c,"""
                SELECT true FROM app_open_meter_configuration_revision m
                WHERE m.project_id=? AND m.source_system=?
                  AND m.version=(SELECT max(version) FROM app_open_meter_configuration_revision
                    WHERE project_id=m.project_id AND meter_code=m.meter_code)
                LIMIT 1
                """,r->true,project,event.subjectCode());
            case "METER" -> first(c,"""
                SELECT true FROM app_open_meter_configuration_revision
                WHERE project_id=? AND meter_code=? LIMIT 1
                """,r->true,project,event.subjectCode());
            case "CHARGER" -> first(c,"""
                SELECT true FROM app_open_charger_revision
                WHERE project_id=? AND charger_code=? LIMIT 1
                """,r->true,project,event.subjectCode());
            default -> null;
        };
        if(!Boolean.TRUE.equals(found))throw invalid("subjectCode",
            "Origem, medidor ou carregador não cadastrado no projeto.");
    }

    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("OCORRENCIA_INVALIDA",field,RULE,message);
    }
    private static PilotProblem conflict(String message) {
        return new PilotProblem(409,"EVENTO_CONFLITANTE","event",RULE,message);
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"EVENTO_INDISPONIVEL","event",RULE,"Recurso indisponível.");
    }
}
