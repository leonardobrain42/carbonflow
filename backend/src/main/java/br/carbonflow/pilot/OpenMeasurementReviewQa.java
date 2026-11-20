package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * CF-OPEN-009: internal, independent measurement QA. A QA review is not
 * measurement admission, a VVB statement, or a permission to run E8.
 */
@ApplicationScoped
class OpenMeasurementReviewQa {
    private static final String RULE="CF-OPEN-009";

    record Decision(String outcome,String reason) {}
    record Review(UUID batchId,int line,int version,String outcome,String reason,
                  UUID reviewedBy,OffsetDateTime reviewedAt) {}
    record Snapshot(UUID id,String projectId,OffsetDateTime from,OffsetDateTime until,
                    String edKwh,String ecKwh,String sourceSha256,
                    String status,boolean usableForE8,UUID createdBy,
                    OffsetDateTime createdAt) {}

    private record Candidate(String state,String batchState,UUID submittedBy,
          String meterCode,Integer meterVersion,String sourceSystem,Instant start,
          Instant end,String kind,BigDecimal kwh) {}

    private final DynamicProjectScope scope;

    OpenMeasurementReviewQa(DynamicProjectScope scope) {
        this.scope=scope;
    }

    List<Review> reviews(String cookie,String project,UUID batch) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,context)->{
            requireBatch(c,project,batch);
            return query(c,"""
                SELECT DISTINCT ON (source_line) batch_id,source_line,version,outcome,
                       reason,reviewed_by,reviewed_at
                FROM app_open_measurement_review
                WHERE project_id=? AND batch_id=?
                ORDER BY source_line,version DESC
                """,OpenMeasurementReviewQa::mapReview,project,batch);
        });
    }

    Review review(String cookie,String project,UUID batch,int line,
                  String ifMatch,Decision input) {
        return scope.write(cookie,project,"REVIEW_OPEN_MEASUREMENTS",(c,context)->{
            // The DRAFT_OWNER is not promoted to an independent reviewer.
            if(context.owner() || !"REV".equals(context.role()))throw unavailable();
            if(input==null || input.outcome()==null ||
                !List.of("ACCEPTED_FOR_QA","REJECTED").contains(input.outcome()) ||
                input.reason()==null || input.reason().strip().length()<15 ||
                input.reason().strip().length()>2000) {
                throw PilotProblem.invalid("PARECER_INVALIDO","reason",RULE,
                    "Informe decisão e justificativa entre 15 e 2000 caracteres.");
            }
            int expected=PilotCommands.expected(ifMatch);
            Candidate candidate=first(c,"""
                SELECT r.state AS row_state,b.status AS batch_state,b.submitted_by,
                       r.meter_code,r.meter_version,b.source_system,r.start_at,
                       r.end_at,r.energy_kind,r.kwh
                FROM app_open_measurement_candidate r
                JOIN app_open_measurement_batch b
                  ON b.project_id=r.project_id AND b.id=r.batch_id
                WHERE r.project_id=? AND r.batch_id=? AND r.source_line=?
                """,r->new Candidate(r.getString("row_state"),r.getString("batch_state"),
                    r.getObject("submitted_by",UUID.class),r.getString("meter_code"),
                    (Integer)r.getObject("meter_version"),r.getString("source_system"),
                    instant(r,"start_at"),instant(r,"end_at"),r.getString("energy_kind"),
                    r.getBigDecimal("kwh")),project,batch,line);
            if(candidate==null)throw unavailable();
            if(context.actor().id().equals(candidate.submittedBy()))
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                    "Quem submeteu o lote não decide a sua admissibilidade interna.");
            Review previous=first(c,"""
                SELECT batch_id,source_line,version,outcome,reason,reviewed_by,reviewed_at
                FROM app_open_measurement_review
                WHERE project_id=? AND batch_id=? AND source_line=?
                ORDER BY version DESC LIMIT 1
                """,OpenMeasurementReviewQa::mapReview,project,batch,line);
            String reason=input.reason().strip();
            if(previous!=null && previous.version()==expected+1 &&
               previous.reviewedBy().equals(context.actor().id()) &&
               previous.outcome().equals(input.outcome()) &&
               previous.reason().equals(reason))return previous;
            PilotCommands.requireVersion(expected,previous==null?0:previous.version());
            if(expected>=100000000)throw PilotProblem.invalid("PARECER_INVALIDO",
                "version",RULE,"Número máximo de revisões atingido.");
            if("ACCEPTED_FOR_QA".equals(input.outcome())) {
                if(!"CANDIDATE".equals(candidate.state()) ||
                   !"CANDIDATE".equals(candidate.batchState()) ||
                   candidate.kwh()==null || candidate.kwh().signum()<=0 ||
                   candidate.start()==null || candidate.end()==null)
                    throw conflict("Lote ou leitura não pode ser aceito: precisa de correção em outro upload.");
                ensureCurrentMeter(c,project,candidate);
                Long competing=first(c,"""
                    SELECT count(*) FROM app_open_measurement_candidate r
                    JOIN app_open_measurement_batch b
                      ON b.project_id=r.project_id AND b.id=r.batch_id
                    JOIN LATERAL (
                      SELECT outcome FROM app_open_measurement_review v
                      WHERE v.project_id=r.project_id AND v.batch_id=r.batch_id
                        AND v.source_line=r.source_line
                      ORDER BY version DESC LIMIT 1
                    ) d ON true
                    WHERE r.project_id=? AND b.source_system=?
                      AND r.meter_code=? AND r.source_sequence=(
                        SELECT source_sequence FROM app_open_measurement_candidate
                        WHERE project_id=? AND batch_id=? AND source_line=?)
                      AND r.start_at=? AND r.end_at=? AND d.outcome='ACCEPTED_FOR_QA'
                      AND NOT (r.batch_id=? AND r.source_line=?)
                    """,r->r.getLong(1),project,candidate.sourceSystem(),
                    candidate.meterCode(),project,batch,line,
                    candidate.start().atOffset(java.time.ZoneOffset.UTC),
                    candidate.end().atOffset(java.time.ZoneOffset.UTC),batch,line);
                if(competing!=null && competing>0)
                    throw conflict("Outra leitura para a mesma origem, medidor, sequência e hora já possui parecer vigente.");
            }
            update(c,"""
                INSERT INTO app_open_measurement_review
                 (project_id,batch_id,source_line,version,outcome,reason,reviewed_by)
                VALUES (?,?,?,?,?,?,?)
                """,project,batch,line,expected+1,input.outcome(),reason,context.actor().id());
            return first(c,"""
                SELECT batch_id,source_line,version,outcome,reason,reviewed_by,reviewed_at
                FROM app_open_measurement_review
                WHERE project_id=? AND batch_id=? AND source_line=? AND version=?
                """,OpenMeasurementReviewQa::mapReview,project,batch,line,expected+1);
        });
    }

    OpenPeriodQa.Result preview(String cookie,String project,
                                OffsetDateTime from,OffsetDateTime until) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->
            evaluate(c,project,from,until));
    }

    Snapshot freeze(String cookie,String project,OffsetDateTime from,OffsetDateTime until) {
        return scope.write(cookie,project,"SNAPSHOT_OPEN_PERIOD",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            var qa=evaluate(c,project,from,until);
            if(!qa.readyForInternalSnapshot())
                throw conflict("O período ainda possui lacunas, conflitos ou revisões pendentes.");
            String hash=hash(project,from,until,qa.accepted());
            byte[] digest=HexFormat.of().parseHex(hash);
            Snapshot old=first(c,"""
                SELECT id,project_id,period_start,period_end,ed_kwh,ec_kwh,
                       encode(source_sha256,'hex') AS sha,status,usable_for_e8,
                       created_by,created_at
                FROM app_open_period_qa_snapshot
                WHERE project_id=? AND period_start=? AND period_end=? AND source_sha256=?
                """,OpenMeasurementReviewQa::mapSnapshot,project,from,until,digest);
            if(old!=null)return old;
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_period_qa_snapshot
                  (id,project_id,organization_id,environment,period_start,period_end,
                   ed_kwh,ec_kwh,source_sha256,created_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),from,until,
                new BigDecimal(qa.edKwh()),new BigDecimal(qa.ecKwh()),digest,ctx.actor().id());
            for(var r:qa.accepted()) {
                update(c,"""
                    INSERT INTO app_open_period_qa_snapshot_line
                    (project_id,snapshot_id,batch_id,source_line,review_version)
                    VALUES (?,?,?,?,?)
                    """,project,id,r.batch(),r.line(),r.reviewVersion());
            }
            return first(c,"""
                SELECT id,project_id,period_start,period_end,ed_kwh,ec_kwh,
                       encode(source_sha256,'hex') AS sha,status,usable_for_e8,
                       created_by,created_at
                FROM app_open_period_qa_snapshot WHERE project_id=? AND id=?
                """,OpenMeasurementReviewQa::mapSnapshot,project,id);
        });
    }

    List<Snapshot> snapshots(String cookie,String project) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->
            query(c,"""
                SELECT id,project_id,period_start,period_end,ed_kwh,ec_kwh,
                       encode(source_sha256,'hex') AS sha,status,usable_for_e8,
                       created_by,created_at
                FROM app_open_period_qa_snapshot WHERE project_id=?
                ORDER BY created_at DESC,id DESC LIMIT 30
                """,OpenMeasurementReviewQa::mapSnapshot,project));
    }

    private static void requireBatch(Connection c,String project,UUID batch) throws SQLException {
        Boolean ok=first(c,"""
            SELECT true FROM app_open_measurement_batch
            WHERE project_id=? AND id=?
            """,r->true,project,batch);
        if(!Boolean.TRUE.equals(ok))throw unavailable();
    }

    private static void ensureCurrentMeter(Connection c,String project,Candidate row)
        throws SQLException {
        Boolean current=first(c,"""
            SELECT true
            FROM app_open_meter_configuration_revision m
            JOIN app_open_charger_revision ch ON ch.project_id=m.project_id
               AND ch.charger_code=m.charger_code AND ch.version=m.charger_version
            WHERE m.project_id=? AND m.meter_code=? AND m.version=?
              AND m.version=(SELECT max(version)
                 FROM app_open_meter_configuration_revision
                 WHERE project_id=m.project_id AND meter_code=m.meter_code)
              AND ch.version=(SELECT max(version) FROM app_open_charger_revision
                 WHERE project_id=ch.project_id AND charger_code=ch.charger_code)
              AND m.energy_kind=? AND m.source_system=?
              AND ch.charger_kind='DCFC' AND ch.grid='SIN' AND ch.topology='GRID_ONLY'
              AND m.calibration_evidence_ref IS NOT NULL
              AND m.calibration_valid_from <= ? AND m.calibration_valid_until >= ?
              AND m.configured_from <= ? AND m.configured_until >= ?
            """,r->true,project,row.meterCode(),row.meterVersion(),row.kind(),
                row.sourceSystem(),row.start().atOffset(java.time.ZoneOffset.UTC),
                row.end().atOffset(java.time.ZoneOffset.UTC),
                row.start().atOffset(java.time.ZoneOffset.UTC),
                row.end().atOffset(java.time.ZoneOffset.UTC));
        if(!Boolean.TRUE.equals(current))
            throw conflict("Medidor, configuração atual, topologia ou calibração não comprova esta leitura.");
    }

    private static OpenPeriodQa.Result evaluate(Connection c,String project,
                                          OffsetDateTime from,OffsetDateTime until)
        throws SQLException {
        // Validate before querying; bound the requested period to 744 hours.
        OpenPeriodQa.evaluate(from,until,List.of(),List.of(),List.of());
        List<String> chargers=query(c,"""
            SELECT DISTINCT ON (charger_code) charger_code
            FROM app_open_charger_revision WHERE project_id=?
            ORDER BY charger_code,version DESC
            """,r->r.getString(1),project);
        List<OpenPeriodQa.Meter> meters=query(c,"""
            SELECT DISTINCT ON (m.meter_code) m.meter_code,m.version,m.charger_code,
              m.energy_kind,ch.charger_kind,ch.grid,ch.topology,
              (m.calibration_evidence_ref IS NOT NULL) AS has_certificate,
              m.calibration_valid_from,m.calibration_valid_until,
              m.configured_from,m.configured_until
            FROM app_open_meter_configuration_revision m
            JOIN app_open_charger_revision ch ON ch.project_id=m.project_id
              AND ch.charger_code=m.charger_code AND ch.version=m.charger_version
            WHERE m.project_id=?
            ORDER BY m.meter_code,m.version DESC
            """,r->new OpenPeriodQa.Meter(r.getString("meter_code"),r.getInt("version"),
                r.getString("charger_code"),r.getString("energy_kind"),
                r.getString("charger_kind"),r.getString("grid"),r.getString("topology"),
                r.getBoolean("has_certificate"),instant(r,"calibration_valid_from"),
                instant(r,"calibration_valid_until"),instant(r,"configured_from"),
                instant(r,"configured_until")),project);
        List<OpenPeriodQa.Reading> rows=query(c,"""
            SELECT r.batch_id,r.source_line,r.meter_code,r.meter_version,r.energy_kind,
                   r.start_at,r.end_at,r.kwh,r.state AS row_state,b.status AS batch_state,
                   v.outcome,v.version AS review_version,
                   encode(e.sha256,'hex') AS source_sha,
                   encode(r.source_line_sha256,'hex') AS line_sha
            FROM app_open_measurement_candidate r
            JOIN app_open_measurement_batch b
              ON b.project_id=r.project_id AND b.id=r.batch_id
            JOIN app_project_evidence_blob e
              ON e.project_id=b.project_id AND e.id=b.evidence_blob_id
            LEFT JOIN LATERAL (
              SELECT outcome,version FROM app_open_measurement_review rev
              WHERE rev.project_id=r.project_id AND rev.batch_id=r.batch_id
                AND rev.source_line=r.source_line
              ORDER BY version DESC LIMIT 1
            ) v ON true
            WHERE r.project_id=? AND r.start_at < ? AND r.end_at > ?
            ORDER BY r.start_at,r.batch_id,r.source_line
            """,r->new OpenPeriodQa.Reading(r.getObject("batch_id",UUID.class),
              r.getInt("source_line"),r.getString("meter_code"),
              r.getObject("meter_version")==null?0:r.getInt("meter_version"),
              r.getString("energy_kind"),instant(r,"start_at"),instant(r,"end_at"),
              r.getBigDecimal("kwh"),r.getString("row_state"),r.getString("batch_state"),
              r.getString("outcome"),r.getObject("review_version")==null?0:r.getInt("review_version"),
              r.getString("source_sha"),r.getString("line_sha")),project,until,from);
        return OpenPeriodQa.evaluate(from,until,chargers,meters,rows);
    }

    private static Instant instant(java.sql.ResultSet r,String field) throws SQLException {
        var value=r.getObject(field,OffsetDateTime.class);
        return value==null?null:value.toInstant();
    }
    private static Review mapReview(java.sql.ResultSet r) throws SQLException {
        return new Review(r.getObject("batch_id",UUID.class),r.getInt("source_line"),
            r.getInt("version"),r.getString("outcome"),r.getString("reason"),
            r.getObject("reviewed_by",UUID.class),
            r.getObject("reviewed_at",OffsetDateTime.class));
    }
    private static Snapshot mapSnapshot(java.sql.ResultSet r) throws SQLException {
        return new Snapshot(r.getObject("id",UUID.class),r.getString("project_id"),
            r.getObject("period_start",OffsetDateTime.class),
            r.getObject("period_end",OffsetDateTime.class),
            r.getBigDecimal("ed_kwh").toPlainString(),
            r.getBigDecimal("ec_kwh").toPlainString(),r.getString("sha"),
            r.getString("status"),r.getBoolean("usable_for_e8"),
            r.getObject("created_by",UUID.class),
            r.getObject("created_at",OffsetDateTime.class));
    }
    private static String hash(String project,OffsetDateTime from,OffsetDateTime until,
                               List<OpenPeriodQa.Ref> refs) {
        StringBuilder payload=new StringBuilder(project).append('|')
            .append(from.toInstant()).append('|').append(until.toInstant());
        for(var ref:refs) {
            payload.append('|').append(ref.batch()).append(':').append(ref.line())
              .append(':').append(ref.reviewVersion()).append(':')
              .append(ref.meter()).append(':').append(ref.version())
              .append(':').append(ref.start()).append(':').append(ref.end())
              .append(':').append(ref.sourceHash()).append(':').append(ref.lineHash());
        }
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
               .digest(payload.toString().getBytes(StandardCharsets.UTF_8)));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static PilotProblem conflict(String message) {
        return new PilotProblem(409,"QA_NAO_ADMISSIVEL","measurement",RULE,message);
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"REVISAO_INDISPONIVEL","measurement",RULE,
            "Registro indisponível.");
    }
}
