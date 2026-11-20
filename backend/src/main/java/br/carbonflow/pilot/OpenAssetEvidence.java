package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.*;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/**
 * Contextual evidence for OPEN stations and DCFC inventory.
 * Reuses the protected project blob of #98; never copies evidence bytes to a second store.
 * An uploaded PDF is merely RECEIVED, never normatively admitted by software.
 */
@ApplicationScoped
class OpenAssetEvidence {
    private static final String RULE="CF-UX-TECH-001";
    record Review(int version,String outcome,String reason,UUID reviewer,OffsetDateTime reviewedAt) {}
    record Document(UUID id,String subjectType,String subjectCode,int subjectVersion,
        String purpose,String evidenceReference,String locator,String fileName,String sha256,
        int bytesCount,UUID submittedBy,OffsetDateTime submittedAt,
        Review review,String state,boolean stale,boolean usableForE8) {}
    record Catalog(String subjectType,String subjectCode,int currentVersion,
        List<Document> documents,boolean canUpload,boolean canReview) {}
    record Decision(String outcome,String reason) {}
    private record Receipt(UUID document,byte[] fingerprint) {}
    private record Target(String type,String code,String column,String versionColumn) {}
    private final DynamicProjectScope scopes;
    private final OpenMeasurementIngestion storage;

    OpenAssetEvidence(DynamicProjectScope scopes,OpenMeasurementIngestion storage){
        this.scopes=scopes;this.storage=storage;
    }

    Catalog list(String token,String project,String type,String code){
        Target target=target(type,code);
        return scopes.read(token,project,"VIEW_OPEN_MONITORING",(c,scope)->{
            int latest=latest(c,project,target);
            return new Catalog(target.type(),target.code(),latest,
                documents(c,project,target),
                scope.owner()||scope.actions().contains("SAVE_OPEN_MONITORING"),
                "REV".equals(scope.role())&&!scope.owner());
        });
    }

    Document upload(String token,String project,String type,String code,int version,
                    String purpose,String reference,String locator,String filename,
                    String key,byte[] bytes){
        Target target=target(type,code);
        return scopes.write(token,project,"SAVE_OPEN_MONITORING",(c,scope)->{
            String command=PilotCommands.key(key);
            if(version<1)throw invalid("version","Informe uma revisão válida do ativo.");
            if(!allowed(target.type(),purpose))throw invalid("purpose",
                "Selecione uma categoria documental válida para estação ou carregador.");
            String ref=clean(reference,8,300,true,"reference");
            String location=clean(locator,3,160,false,"locator");
            // A PDF's header is necessary, not proof of its authenticity.
            if(bytes==null||bytes.length<16||bytes.length>2_000_000||
                !"%PDF-".equals(new String(bytes,0,Math.min(5,bytes.length),
                    StandardCharsets.US_ASCII)))
                throw invalid("file","Envie um PDF de até 2 MB, com cabeçalho PDF.");
            String digest=FixtureCatalog.sha256(bytes);
            byte[] fingerprint=AccessSecrets.digest(
                "ASSET-PDF|"+project+"|"+target.type()+"|"+target.code()+"|"+
                version+"|"+purpose+"|"+ref+"|"+location+"|"+filename+"|"+digest);
            Receipt replay=first(c,"""
                SELECT document_id,fingerprint FROM app_open_asset_evidence_command
                WHERE actor_id=? AND command_key=?
                """,r->new Receipt(r.getObject(1,UUID.class),r.getBytes(2)),
                scope.actor().id(),command);
            if(replay!=null){
                if(!MessageDigest.isEqual(replay.fingerprint(),fingerprint))
                    throw new PilotProblem(409,"CHAVE_REUSADA","Idempotency-Key",RULE,
                        "Esta chave já foi utilizada para outra prova.");
                return document(c,project,replay.document());
            }
            int current=latest(c,project,target);
            if(version!=current)
                throw new PilotProblem(412,"ATIVO_DESATUALIZADO","version",RULE,
                    "A estação ou equipamento mudou. Recarregue o cadastro antes de anexar.");
            UUID blob=storage.storeSupportPdf(c,scope,filename,bytes);
            String sql="SELECT id FROM app_open_asset_evidence WHERE project_id=? AND "+
                target.column()+"=? AND "+target.versionColumn()+"=? AND purpose=? AND evidence_blob_id=?";
            UUID existing=first(c,sql,r->r.getObject(1,UUID.class),project,target.code(),version,purpose,blob);
            UUID id=existing==null?UUID.randomUUID():existing;
            if(existing==null){
                update(c,"""
                    INSERT INTO app_open_asset_evidence
                    (id,project_id,organization_id,environment,site_code,site_version,
                     charger_code,charger_version,purpose,evidence_blob_id,evidence_reference,
                     locator,submitted_by)
                    VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?)
                    """,id,project,scope.organization(),scope.environment(),
                    "SITE".equals(target.type())?target.code():null,
                    "SITE".equals(target.type())?version:null,
                    "CHARGER".equals(target.type())?target.code():null,
                    "CHARGER".equals(target.type())?version:null,
                    purpose,blob,ref,location,scope.actor().id());
            }
            update(c,"""
                INSERT INTO app_open_asset_evidence_command
                 (actor_id,command_key,project_id,fingerprint,document_id)
                VALUES(?,?,?,?,?)
                """,scope.actor().id(),command,project,fingerprint,id);
            return document(c,project,id);
        });
    }

    Review review(String token,String project,UUID id,String match,Decision input){
        return scopes.write(token,project,"VIEW_OPEN_MONITORING",(c,scope)->{
            if(scope.owner()||!"REV".equals(scope.role()))throw hidden();
            Document doc=document(c,project,id);
            if(doc.submittedBy().equals(scope.actor().id()))
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                    "Quem anexou o PDF não pode revisar a própria prova.");
            if(doc.stale())throw new PilotProblem(409,"PROVA_OBSOLETA","document",RULE,
                "A prova pertence a uma versão anterior do ativo. Reavalie a versão atual.");
            if(input==null||!Set.of("DOCUMENT_CHECKED_INTERNAL","REJECTED").contains(input.outcome())||
                input.reason()==null||input.reason().strip().length()<15||
                input.reason().strip().length()>2000)
                throw invalid("decision","Selecione parecer interno e motivo entre 15 e 2000 caracteres.");
            int expected=PilotCommands.expected(match);
            Review previous=doc.review();
            String reason=input.reason().strip();
            if(previous!=null&&previous.version()==expected+1&&
                previous.reviewer().equals(scope.actor().id())&&
                previous.outcome().equals(input.outcome())&&previous.reason().equals(reason))
                return previous;
            PilotCommands.requireVersion(expected,previous==null?0:previous.version());
            if(expected>=100000000)throw invalid("version","Limite de revisões atingido.");
            update(c,"""
                INSERT INTO app_open_asset_evidence_review
                (project_id,document_id,version,outcome,reason,reviewed_by)
                VALUES(?,?,?,?,?,?)
                """,project,id,expected+1,input.outcome(),reason,scope.actor().id());
            return document(c,project,id).review();
        });
    }

    private static Target target(String type,String code){
        String safe=OpenMonitoringConfiguration.code(code);
        return switch(type==null?"":type.toUpperCase(Locale.ROOT)){
            case "SITE" -> new Target("SITE",safe,"site_code","site_version");
            case "CHARGER" -> new Target("CHARGER",safe,"charger_code","charger_version");
            default -> throw invalid("subjectType","Escolha estação ou carregador.");
        };
    }
    private static boolean allowed(String type,String purpose){
        return "SITE".equals(type)?Set.of("LOCATION","COMMISSIONING","PUBLIC_ACCESS").contains(purpose):
            Set.of("SPECIFICATION","INSTALLATION","GRID_CONNECTION").contains(purpose);
    }
    private static int latest(Connection c,String project,Target target) throws SQLException {
        String table="SITE".equals(target.type())?"app_open_site_revision":"app_open_charger_revision";
        Integer value=first(c,"SELECT max(version) FROM "+table+" WHERE project_id=? AND "+
            target.column()+"=?",r->r.getObject(1,Integer.class),project,target.code());
        if(value==null)throw hidden();
        return value;
    }
    private static List<Document> documents(Connection c,String project,Target target)
        throws SQLException {
        String filter=target.column();
        return query(c,BASE+" WHERE d.project_id=? AND d."+filter+"=?"+
            " ORDER BY d.submitted_at DESC,d.id DESC",
            r->map(c,project,r),project,target.code());
    }
    private static Document document(Connection c,String project,UUID id) throws SQLException {
        Document value=first(c,BASE+" WHERE d.project_id=? AND d.id=?",
            r->map(c,project,r),project,id);
        if(value==null)throw hidden();
        return value;
    }
    private static final String BASE="""
        SELECT d.id,d.site_code,d.site_version,d.charger_code,d.charger_version,
          d.purpose,d.evidence_reference,d.locator,d.submitted_by,d.submitted_at,
          o.file_name,encode(o.sha256,'hex') AS sha256,o.size_bytes
        FROM app_open_asset_evidence d
        JOIN app_project_evidence_blob o ON o.project_id=d.project_id AND o.id=d.evidence_blob_id
        """;
    private static Document map(Connection c,String project,java.sql.ResultSet r)
        throws SQLException {
        boolean site=r.getString("site_code")!=null;
        String type=site?"SITE":"CHARGER";
        String code=r.getString(site?"site_code":"charger_code");
        int version=r.getInt(site?"site_version":"charger_version");
        boolean stale=latest(c,project,target(type,code))!=version;
        UUID id=r.getObject("id",UUID.class);
        Review review=first(c,"""
            SELECT version,outcome,reason,reviewed_by,reviewed_at
            FROM app_open_asset_evidence_review
            WHERE project_id=? AND document_id=? ORDER BY version DESC LIMIT 1
            """,a->new Review(a.getInt(1),a.getString(2),a.getString(3),
                a.getObject(4,UUID.class),a.getObject(5,OffsetDateTime.class)),project,id);
        String state=stale?"STALE":review==null?"PENDING_REVIEW":
            "DOCUMENT_CHECKED_INTERNAL".equals(review.outcome())?"CHECKED_INTERNAL":"REJECTED";
        return new Document(id,type,code,version,r.getString("purpose"),
            r.getString("evidence_reference"),r.getString("locator"),r.getString("file_name"),
            r.getString("sha256"),r.getInt("size_bytes"),r.getObject("submitted_by",UUID.class),
            r.getObject("submitted_at",OffsetDateTime.class),review,state,stale,false);
    }
    private static String clean(String raw,int min,int max,boolean required,String field){
        if(raw==null||raw.isBlank()){
            if(required)throw invalid(field,"Informe referência ou localizador verificável.");
            return null;
        }
        String value=raw.strip();
        if(value.length()<min||value.length()>max||
            value.codePoints().anyMatch(Character::isISOControl))
            throw invalid(field,"O campo excedeu o tamanho permitido ou possui caracteres inválidos.");
        return value;
    }
    private static PilotProblem hidden(){
        return new PilotProblem(404,"PROVA_INDISPONIVEL","document",RULE,"Evidência indisponível para esta conta.");
    }
    private static PilotProblem invalid(String field,String message){
        return PilotProblem.invalid("PROVA_ATIVO_INVALIDA",field,RULE,message);
    }
}
