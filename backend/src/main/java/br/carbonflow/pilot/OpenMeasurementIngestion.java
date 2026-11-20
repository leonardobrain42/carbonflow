package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Base64;
import java.util.HashMap;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Ingests ORIGINAL CSV evidence into a generic, tenant-scoped AES-GCM blob,
 * then records immutable candidate rows. Never writes to pilot_* and never
 * marks a row ADMITTED, even if its syntax and calibration coverage pass.
 */
@ApplicationScoped
class OpenMeasurementIngestion {
    private static final String RULE = "CF-OPEN-009";
    private static final String MIME = "text/csv";
    private final DynamicProjectScope scope;
    private final byte[] key;

    record Row(int line,String meterCode,String sequence,String kind,
               OffsetDateTime start,OffsetDateTime end,String zone,String kwh,
               String state,List<String> reasons) {}

    record Batch(UUID id,String projectId,String source,String fileName,
                 String sha256,int rowCount,String status,OffsetDateTime submittedAt,
                 List<Row> rows) {}

    OpenMeasurementIngestion(DynamicProjectScope scope,
        @ConfigProperty(name="carbonflow.project.evidence-key") Optional<String> configuredKey) {
        this.scope=scope;
        this.key=configuredKey.filter(v->!v.isBlank())
            .map(v->Base64.getUrlDecoder().decode(v)).orElse(null);
        if(key!=null && key.length!=32)
            throw new IllegalArgumentException("Chave AES de evidência de projeto inválida");
    }

    Batch submit(String cookie,String project,String sourceId,String filename,
                 String idempotencyKey,byte[] original) {
        return scope.write(cookie,project,"SUBMIT_OPEN_MEASUREMENTS",(c,context)->{
            if(key==null)throw new PilotProblem(503,"CHAVE_DE_EVIDENCIA_AUSENTE",
                "file",RULE,"O armazenamento de evidência cifrada não está configurado.");
            String source=OpenMonitoringConfiguration.code(sourceId);
            String keyHeader=PilotCommands.key(idempotencyKey);
            if(filename==null || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{4,115}\\.csv")
                || filename.contains("..")) throw invalid("fileName","Nome CSV inválido.");
            if(original==null || original.length==0 || original.length>2_000_000)
                throw invalid("file","CSV excede o limite de 2 MB.");
            byte[] sha=sha(original);
            byte[] fingerprint=AccessSecrets.digest("MEASUREMENT|"+project+"|"+source+"|"
                +filename+"|"+HexFormat.of().formatHex(sha));
            var prior=first(c,"""
                SELECT batch_id,fingerprint FROM app_open_measurement_upload_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getObject(1,UUID.class),r.getBytes(2)},
                context.actor().id(),keyHeader);
            if(prior!=null) {
                if(!MessageDigest.isEqual((byte[])prior[1],fingerprint))
                    throw new PilotProblem(409,"CHAVE_REUSADA","Idempotency-Key",RULE,
                        "Chave já identifica outro arquivo ou origem.");
                return batch(c,project,(UUID)prior[0]);
            }

            UUID existing=first(c,"""
                SELECT b.id FROM app_open_measurement_batch b
                JOIN app_project_evidence_blob e
                  ON e.project_id=b.project_id AND e.id=b.evidence_blob_id
                WHERE b.project_id=? AND b.source_system=? AND e.sha256=?
                """,r->r.getObject(1,UUID.class),project,source,sha);
            if(existing!=null) {
                update(c,"""
                    INSERT INTO app_open_measurement_upload_command
                     (actor_id,command_key,project_id,fingerprint,batch_id)
                    VALUES (?,?,?,?,?)
                    """,context.actor().id(),keyHeader,project,fingerprint,existing);
                return batch(c,project,existing);
            }

            Map<String,OpenMeasurementCsv.MeterScope> meters=meters(c,project);
            Set<String> previous=identities(c,project,source);
            OpenMeasurementCsv.Preview parsed=OpenMeasurementCsv.parse(original,meters,previous,source);
            UUID blobId=first(c,"""
                SELECT id FROM app_project_evidence_blob
                WHERE project_id=? AND sha256=?
                """,r->r.getObject(1,UUID.class),project,sha);
            if(blobId==null) {
                blobId=UUID.randomUUID();
                byte[] nonce=AccessSecrets.randomBytes(12);
                byte[] encrypted=encrypt(context.organization(),project,blobId,sha,nonce,original);
                update(c,"""
                    INSERT INTO app_project_evidence_blob
                      (id,project_id,organization_id,environment,mime_type,file_name,
                       sha256,nonce,encrypted,size_bytes,uploaded_by)
                    VALUES (?,?,?,?,?,?,?,?,?,?,?)
                    """,blobId,project,context.organization(),context.environment(),MIME,
                    filename,sha,nonce,encrypted,original.length,context.actor().id());
            }
            UUID batchId=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_measurement_batch
                  (id,project_id,organization_id,environment,evidence_blob_id,
                   source_system,parser_version,row_count,status,submitted_by)
                VALUES (?,?,?,?,?,?,?,?,?,?)
                """,batchId,project,context.organization(),context.environment(),blobId,
                source,OpenMeasurementCsv.VERSION,parsed.rows().size(),
                parsed.quarantined()?"QUARANTINED":"CANDIDATE",context.actor().id());
            for(var row:parsed.rows()) {
                var codes=c.createArrayOf("text",row.reasons().toArray(String[]::new));
                try {
                    update(c,"""
                      INSERT INTO app_open_measurement_candidate
                        (project_id,batch_id,source_line,source_sequence,meter_code,
                         meter_version,energy_kind,start_at,end_at,source_zone,kwh,
                         source_line_sha256,state,issue_codes)
                      VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                      """,project,batchId,row.line(),row.sequence(),row.meterCode(),
                        row.meterVersion(),row.kind(),row.start(),row.end(),row.zone(),
                        row.state().equals("CANDIDATE")?row.kwh():null,
                        row.sourceLineSha256(),row.state(),codes);
                } finally {codes.free();}
            }
            update(c,"""
                INSERT INTO app_open_measurement_upload_command
                   (actor_id,command_key,project_id,fingerprint,batch_id)
                VALUES (?,?,?,?,?)
                """,context.actor().id(),keyHeader,project,fingerprint,batchId);
            return batch(c,project,batchId);
        });
    }

    List<Batch> list(String cookie,String project) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->
            query(c,"""
                SELECT id FROM app_open_measurement_batch
                WHERE project_id=? ORDER BY submitted_at DESC,id DESC LIMIT 10
                """,r->batch(c,project,r.getObject(1,UUID.class)),project));
    }

    Batch read(String cookie,String project,UUID id) {
        return scope.read(cookie,project,"VIEW_OPEN_MONITORING",(c,ctx)->batch(c,project,id));
    }

    private static Batch batch(Connection c,String project,UUID id) throws SQLException {
        var header=first(c,"""
            SELECT b.id,b.source_system,e.file_name,encode(e.sha256,'hex') AS sha,
              b.row_count,b.status,b.submitted_at
            FROM app_open_measurement_batch b
            JOIN app_project_evidence_blob e ON e.project_id=b.project_id
               AND e.id=b.evidence_blob_id
            WHERE b.project_id=? AND b.id=?
            """,r->new Object[]{r.getObject("id",UUID.class),r.getString("source_system"),
                r.getString("file_name"),r.getString("sha"),r.getInt("row_count"),
                r.getString("status"),r.getObject("submitted_at",OffsetDateTime.class)},
            project,id);
        if(header==null)throw new PilotProblem(404,"LOTE_NAO_VISIVEL","batch",RULE,
            "Lote indisponível.");
        List<Row> rows=query(c,"""
            SELECT source_line,meter_code,source_sequence,energy_kind,start_at,end_at,
              source_zone,kwh,state,issue_codes
            FROM app_open_measurement_candidate WHERE project_id=? AND batch_id=?
            ORDER BY source_line
            """,r->{
                var sqlArray=r.getArray("issue_codes");
                try {
                    return new Row(r.getInt("source_line"),r.getString("meter_code"),
                        r.getString("source_sequence"),r.getString("energy_kind"),
                        r.getObject("start_at",OffsetDateTime.class),
                        r.getObject("end_at",OffsetDateTime.class),
                        r.getString("source_zone"),
                        r.getBigDecimal("kwh")==null?null:r.getBigDecimal("kwh").toPlainString(),
                        r.getString("state"),List.of((String[])sqlArray.getArray()));
                } finally {sqlArray.free();}
            },project,id);
        return new Batch((UUID)header[0],project,(String)header[1],(String)header[2],
            (String)header[3],(Integer)header[4],(String)header[5],
            (OffsetDateTime)header[6],List.copyOf(rows));
    }

    private static Map<String,OpenMeasurementCsv.MeterScope> meters(
            Connection c,String project) throws SQLException {
        var items=query(c,"""
            SELECT DISTINCT ON (m.meter_code)
              m.meter_code,m.version,m.energy_kind,m.charger_code,
              m.charger_version,c.charger_kind,c.grid,c.topology,m.time_zone,
              m.calibration_valid_from,m.calibration_valid_until,m.source_system,
              m.configured_from,m.configured_until,
              (SELECT max(version) FROM app_open_charger_revision
               WHERE project_id=m.project_id AND charger_code=m.charger_code) AS current_charger_version
            FROM app_open_meter_configuration_revision m
            JOIN app_open_charger_revision c ON c.project_id=m.project_id
                AND c.charger_code=m.charger_code AND c.version=m.charger_version
            WHERE m.project_id=?
            ORDER BY m.meter_code,m.version DESC
            """,r->new OpenMeasurementCsv.MeterScope(
                r.getString("meter_code"),r.getInt("version"),r.getString("energy_kind"),
                r.getString("charger_code"),r.getInt("charger_version"),
                r.getInt("current_charger_version"),r.getString("charger_kind"),
                r.getString("grid"),r.getString("topology"),r.getString("time_zone"),
                r.getString("source_system"),r.getObject("calibration_valid_from",OffsetDateTime.class),
                r.getObject("calibration_valid_until",OffsetDateTime.class),
                r.getObject("configured_from",OffsetDateTime.class),
                r.getObject("configured_until",OffsetDateTime.class)),project);
        var map=new HashMap<String,OpenMeasurementCsv.MeterScope>();
        items.forEach(m->map.put(m.code(),m));
        return map;
    }

    private static Set<String> identities(Connection c,String project,String source)
            throws SQLException {
        List<String> values=query(c,"""
            SELECT r.meter_code,r.source_sequence,r.start_at,r.end_at
            FROM app_open_measurement_candidate r
            JOIN app_open_measurement_batch b ON b.id=r.batch_id AND b.project_id=r.project_id
            WHERE r.project_id=? AND b.source_system=? AND r.meter_code IS NOT NULL
              AND r.source_sequence IS NOT NULL AND r.start_at IS NOT NULL AND r.end_at IS NOT NULL
              AND r.state='CANDIDATE'
            """,r->String.join("|",r.getString(1),r.getString(2),
                r.getObject(3,OffsetDateTime.class).toInstant().toString(),
                r.getObject(4,OffsetDateTime.class).toInstant().toString()),project,source);
        return new HashSet<>(values);
    }

    /**
     * Protected project evidence store reused for PDF documentation. A file
     * matching the PDF envelope is NOT a verified certificate or legal right.
     * Never serve original bytes in a publicly accessible API.
     */
    UUID storeSupportPdf(Connection c,DynamicProjectScope.Context scope,
                         String filename,byte[] original) throws SQLException {
        if(key==null)throw new PilotProblem(503,"CHAVE_DE_EVIDENCIA_AUSENTE",
            "file",RULE,"Chave de evidência cifrada não configurada.");
        if(filename==null || !filename.matches("[A-Za-z0-9][A-Za-z0-9._-]{4,115}\\.pdf")
            || filename.contains("..") || original==null ||
            original.length<16 || original.length>2_000_000) {
            throw PilotProblem.invalid("PROVA_INVALIDA","file",RULE,
                "Envie documento PDF até 2 MB com nome seguro.");
        }
        String signature=new String(original,0,Math.min(5,original.length),
            StandardCharsets.US_ASCII);
        if(!"%PDF-".equals(signature)) {
            throw PilotProblem.invalid("PROVA_INVALIDA","file",RULE,
                "O arquivo não apresenta cabeçalho PDF. Isso não comprova autenticidade.");
        }
        byte[] digest=sha(original);
        UUID existing=first(c,"""
            SELECT id FROM app_project_evidence_blob
            WHERE project_id=? AND sha256=? AND mime_type='application/pdf'
            """,r->r.getObject(1,UUID.class),scope.id(),digest);
        if(existing!=null)return existing;
        UUID id=UUID.randomUUID();
        byte[] nonce=AccessSecrets.randomBytes(12);
        byte[] encrypted=encrypt(scope.organization(),scope.id(),id,digest,nonce,original);
        update(c,"""
            INSERT INTO app_project_evidence_blob
              (id,project_id,organization_id,environment,mime_type,file_name,
               sha256,nonce,encrypted,size_bytes,uploaded_by)
            VALUES (?,?,?,?,?,?,?,?,?,?,?)
            """,id,scope.id(),scope.organization(),scope.environment(),
            "application/pdf",filename,digest,nonce,encrypted,original.length,scope.actor().id());
        return id;
    }

    private byte[] encrypt(UUID organization,String project,UUID blob,byte[] sha,
                           byte[] nonce,byte[] bytes) {
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.ENCRYPT_MODE,new SecretKeySpec(key,"AES"),
                new GCMParameterSpec(128,nonce));
            cipher.updateAAD((organization+"|"+project+"|"+blob+"|"+
                HexFormat.of().formatHex(sha)).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(bytes);
        }catch(GeneralSecurityException e){
            throw new IllegalStateException("Falha ao proteger evidência de medição",e);
        }
    }

    private static byte[] sha(byte[] raw) {
        try {return MessageDigest.getInstance("SHA-256").digest(raw);}
        catch(GeneralSecurityException e){throw new IllegalStateException(e);}
    }
    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("CSV_MEDICAO_INVALIDO",field,RULE,message);
    }
}
