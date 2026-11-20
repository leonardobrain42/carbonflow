package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * CF-OPEN-009: documentary due diligence and live staleness on immutable QA
 * snapshots. NO external rights/certification is inferred, no E8 admission.
 */
@ApplicationScoped
class OpenPeriodSupport {
    private static final String RULE="CF-OPEN-009";
    private final DynamicProjectScope scope;
    private final OpenMeasurementIngestion storage;

    record Review(int version,String outcome,String reason,UUID reviewedBy,
                  OffsetDateTime reviewedAt) {}
    record Document(UUID id,UUID snapshotId,String kind,String subjectCode,
                    String evidenceReference,String fileName,String sha256,
                    int bytesCount,UUID submittedBy,OffsetDateTime submittedAt,Review review) {}
    record Decision(String outcome,String reason) {}
    record Gate(String code,String subject,String detail) {}
    record Readiness(UUID snapshotId,String state,boolean stale,
                     boolean documentaryComplete,boolean usableForE8,
                     String historicalEdKwh,String historicalEcKwh,
                     List<Gate> gates,List<Document> documents) {}

    private record Historical(OffsetDateTime from,OffsetDateTime until,
                              String hash,String ed,String ec,UUID createdBy) {}
    private record Required(String kind,String subject) {}

    OpenPeriodSupport(DynamicProjectScope scope,OpenMeasurementIngestion storage) {
        this.scope=scope;
        this.storage=storage;
    }

    Readiness readiness(String cookie,String project,UUID snapshot) {
        return scope.read(cookie,project,"VIEW_OPEN_PERIOD_ADMISSION",(c,ctx)->
            diagnose(c,project,snapshot));
    }

    Document submit(String cookie,String project,UUID snapshot,String kind,
                    String subject,String reference,String filename,String key,byte[] bytes) {
        return scope.write(cookie,project,"SUBMIT_OPEN_PERIOD_EVIDENCE",(c,ctx)->{
            historical(c,project,snapshot);
            String k=kind(kind),s=OpenMonitoringConfiguration.code(subject);
            if(reference==null || reference.strip().length()<8 ||
               reference.strip().length()>300 ||
               reference.codePoints().anyMatch(Character::isISOControl))
                throw invalid("reference","Informe referência documental verificável de 8 a 300 caracteres.");
            String ref=reference.strip();
            if(!required(c,project,snapshot).contains(new Required(k,s)))
                throw conflict("A prova não corresponde ao medidor ou origem do snapshot.");
            String command=PilotCommands.key(key);
            if(bytes==null || bytes.length<16 || bytes.length>2_000_000)
                throw invalid("file","Arquivo PDF inválido ou maior que 2 MB.");
            String digest=shaHex(bytes);
            byte[] fingerprint=AccessSecrets.digest("SUPPORT|"+project+"|"+snapshot+
                "|"+k+"|"+s+"|"+ref+"|"+filename+"|"+digest);
            Object[] previous=first(c,"""
                SELECT document_id,fingerprint FROM app_open_period_support_upload_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getObject(1,UUID.class),r.getBytes(2)},ctx.actor().id(),command);
            if(previous!=null) {
                if(!MessageDigest.isEqual(fingerprint,(byte[])previous[1]))
                    throw conflict("Idempotency-Key reutilizada com outro documento.");
                return document(c,project,(UUID)previous[0]);
            }
            UUID blob=storage.storeSupportPdf(c,ctx,filename,bytes);
            UUID existing=first(c,"""
                SELECT id FROM app_open_period_support_document
                WHERE project_id=? AND snapshot_id=? AND kind=? AND subject_code=?
                  AND evidence_blob_id=?
                """,r->r.getObject(1,UUID.class),project,snapshot,k,s,blob);
            UUID id=existing==null?UUID.randomUUID():existing;
            if(existing==null) {
                update(c,"""
                    INSERT INTO app_open_period_support_document
                    (id,project_id,snapshot_id,kind,subject_code,evidence_blob_id,
                     evidence_reference,submitted_by)
                    VALUES(?,?,?,?,?,?,?,?)
                    """,id,project,snapshot,k,s,blob,ref,ctx.actor().id());
            }
            update(c,"""
                INSERT INTO app_open_period_support_upload_command
                  (actor_id,command_key,project_id,fingerprint,document_id)
                VALUES (?,?,?,?,?)
                """,ctx.actor().id(),command,project,fingerprint,id);
            return document(c,project,id);
        });
    }

    Review review(String cookie,String project,UUID document,String ifMatch,Decision choice) {
        return scope.write(cookie,project,"REVIEW_OPEN_PERIOD_EVIDENCE",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            Document doc=document(c,project,document);
            Historical historical=historical(c,project,doc.snapshotId());
            if(doc.submittedBy().equals(ctx.actor().id()) ||
               historical.createdBy().equals(ctx.actor().id()))
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                    "Revisor não pode ser autor da prova nem do snapshot de QA.");
            if(choice==null || choice.outcome()==null || choice.reason()==null || choice.reason().strip().length()<15
                || choice.reason().strip().length()>2000 ||
                !Set.of("DOCUMENT_CHECKED_INTERNAL","REJECTED").contains(choice.outcome()))
                throw invalid("reason","Informe decisão e justificativa entre 15 e 2000 caracteres.");
            int expected=PilotCommands.expected(ifMatch);
            Review old=doc.review();
            String reason=choice.reason().strip();
            if(old!=null && old.version()==expected+1 &&
               old.reviewedBy().equals(ctx.actor().id()) &&
               old.outcome().equals(choice.outcome()) && old.reason().equals(reason))
                return old;
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if(expected>=100000000)throw invalid("version","Limite máximo de versões.");
            update(c,"""
                INSERT INTO app_open_period_support_review
                (project_id,document_id,version,outcome,reason,reviewed_by)
                VALUES (?,?,?,?,?,?)
                """,project,document,expected+1,choice.outcome(),reason,ctx.actor().id());
            return document(c,project,document).review();
        });
    }

    private static Readiness diagnose(Connection c,String project,UUID snapshot) throws SQLException {
        Historical frozen=historical(c,project,snapshot);
        var gates=new ArrayList<Gate>();
        var current=OpenMeasurementReviewQa.evaluate(c,project,frozen.from(),frozen.until());
        boolean stale=!current.readyForInternalSnapshot();
        if(stale) {
            gates.add(new Gate("SNAPSHOT_QA_STALE",null,
                "Dados ou configuração atuais já não satisfazem o QA das horas do snapshot."));
        } else {
            String nowHash=OpenMeasurementReviewQa.hash(project,
                frozen.from(),frozen.until(),current.accepted(),
                OpenMonitoringEvents.impacting(c,project,frozen.from(),frozen.until()));
            if(!nowHash.equals(frozen.hash())) {
                stale=true;
                gates.add(new Gate("SNAPSHOT_REVISIONS_CHANGED",null,
                    "Linhas, versões dos pareceres ou fontes foram alteradas após o congelamento."));
            }
        }
        List<Required> needed=required(c,project,snapshot);
        List<Document> all=documents(c,project,snapshot);
        var latest=new HashMap<Required,Document>();
        for(Document doc:all) {
            latest.putIfAbsent(new Required(doc.kind(),doc.subjectCode()),doc);
        }
        if(needed.isEmpty()) {
            gates.add(new Gate("NO_MEASUREMENT_LINEAGE",null,
                "O snapshot não identifica medidores e fontes de origem."));
        }
        for(Required item:needed) {
            Document proof=latest.get(item);
            if(proof==null) {
                gates.add(new Gate("SUPPORT_DOCUMENT_MISSING",item.kind()+":"+item.subject(),
                    "Anexe PDF de calibração ou de autorização de uso dos dados da origem."));
            } else if(proof.review()==null) {
                gates.add(new Gate("SUPPORT_REVIEW_PENDING",item.kind()+":"+item.subject(),
                    "O PDF está protegido e registrado, mas exige análise independente."));
            } else if(!"DOCUMENT_CHECKED_INTERNAL".equals(proof.review().outcome())) {
                gates.add(new Gate("SUPPORT_DOCUMENT_REJECTED",item.kind()+":"+item.subject(),
                    "A prova documental mais recente foi rejeitada: forneça correção e nova revisão."));
            }
        }
        boolean ready=gates.isEmpty();
        return new Readiness(snapshot,stale?"BLOCKED_STALE":
            ready?"READY_FOR_NORMATIVE_REVIEW":"PENDING_DOCUMENTARY_REVIEW",
            stale,ready,false,frozen.ed(),frozen.ec(),List.copyOf(gates),List.copyOf(all));
    }

    private static Historical historical(Connection c,String project,UUID snapshot)
        throws SQLException {
        Historical value=first(c,"""
            SELECT period_start,period_end,encode(source_sha256,'hex') AS sha,
                   ed_kwh,ec_kwh,created_by
            FROM app_open_period_qa_snapshot WHERE project_id=? AND id=?
            """,r->new Historical(r.getObject("period_start",OffsetDateTime.class),
                r.getObject("period_end",OffsetDateTime.class),
                r.getString("sha"),r.getBigDecimal("ed_kwh").toPlainString(),
                r.getBigDecimal("ec_kwh").toPlainString(),
                r.getObject("created_by",UUID.class)),project,snapshot);
        if(value==null)throw unavailable();
        return value;
    }

    private static List<Required> required(Connection c,String project,UUID snapshot)
        throws SQLException {
        var subjects=query(c,"""
            SELECT DISTINCT r.meter_code,b.source_system
            FROM app_open_period_qa_snapshot_line l
            JOIN app_open_measurement_candidate r
              ON r.project_id=l.project_id AND r.batch_id=l.batch_id
              AND r.source_line=l.source_line
            JOIN app_open_measurement_batch b
              ON b.project_id=r.project_id AND b.id=r.batch_id
            WHERE l.project_id=? AND l.snapshot_id=?
            ORDER BY r.meter_code,b.source_system
            """,r->new String[]{r.getString(1),r.getString(2)},project,snapshot);
        var required=new HashSet<Required>();
        for(String[] pair:subjects) {
            required.add(new Required("METER_CALIBRATION",pair[0]));
            required.add(new Required("SOURCE_RIGHTS",pair[1]));
        }
        return required.stream().sorted((a,b)->(a.kind()+a.subject()).compareTo(b.kind()+b.subject())).toList();
    }

    private static List<Document> documents(Connection c,String project,UUID snapshot)
        throws SQLException {
        return query(c,"""
            SELECT d.id,d.snapshot_id,d.kind,d.subject_code,d.evidence_reference,
              o.file_name,encode(o.sha256,'hex') AS sha,o.size_bytes,
              d.submitted_by,d.submitted_at
            FROM app_open_period_support_document d
            JOIN app_project_evidence_blob o
              ON o.project_id=d.project_id AND o.id=d.evidence_blob_id
            WHERE d.project_id=? AND d.snapshot_id=?
            ORDER BY d.submitted_at DESC,d.id DESC
            """,r->new Document(r.getObject("id",UUID.class),
                r.getObject("snapshot_id",UUID.class),r.getString("kind"),
                r.getString("subject_code"),r.getString("evidence_reference"),
                r.getString("file_name"),r.getString("sha"),r.getInt("size_bytes"),
                r.getObject("submitted_by",UUID.class),
                r.getObject("submitted_at",OffsetDateTime.class),
                latestReview(c,project,r.getObject("id",UUID.class))),project,snapshot);
    }

    private static Document document(Connection c,String project,UUID id) throws SQLException {
        Document value=first(c,"""
            SELECT d.id,d.snapshot_id,d.kind,d.subject_code,d.evidence_reference,
              o.file_name,encode(o.sha256,'hex') AS sha,o.size_bytes,
              d.submitted_by,d.submitted_at
            FROM app_open_period_support_document d
            JOIN app_project_evidence_blob o
              ON o.project_id=d.project_id AND o.id=d.evidence_blob_id
            WHERE d.project_id=? AND d.id=?
            """,r->new Document(r.getObject("id",UUID.class),
                r.getObject("snapshot_id",UUID.class),r.getString("kind"),
                r.getString("subject_code"),r.getString("evidence_reference"),
                r.getString("file_name"),r.getString("sha"),r.getInt("size_bytes"),
                r.getObject("submitted_by",UUID.class),
                r.getObject("submitted_at",OffsetDateTime.class),
                latestReview(c,project,r.getObject("id",UUID.class))),project,id);
        if(value==null)throw unavailable();
        return value;
    }

    private static Review latestReview(Connection c,String project,UUID id) throws SQLException {
        return first(c,"""
            SELECT version,outcome,reason,reviewed_by,reviewed_at
            FROM app_open_period_support_review
            WHERE project_id=? AND document_id=? ORDER BY version DESC LIMIT 1
            """,r->new Review(r.getInt("version"),r.getString("outcome"),
                r.getString("reason"),r.getObject("reviewed_by",UUID.class),
                r.getObject("reviewed_at",OffsetDateTime.class)),project,id);
    }

    private static String kind(String kind) {
        if(!Set.of("METER_CALIBRATION","SOURCE_RIGHTS").contains(kind))
            throw invalid("kind","Tipo de comprovação desconhecido.");
        return kind;
    }

    private static String shaHex(byte[] raw) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(raw));
        }catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }

    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("PROVA_INVALIDA",field,RULE,message);
    }
    private static PilotProblem conflict(String message) {
        return new PilotProblem(409,"EVIDENCIA_CONFLITANTE","document",RULE,message);
    }
    private static PilotProblem unavailable() {
        return new PilotProblem(404,"PROVA_INDISPONIVEL","document",RULE,"Recurso indisponível.");
    }
}
