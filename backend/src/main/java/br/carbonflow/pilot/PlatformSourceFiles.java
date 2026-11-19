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
import java.util.Arrays;
import java.util.Base64;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Authorized originals are AES-GCM sealed and stay quarantined. Parsing creates
 * dimensionally checked candidates; only a separate reviewer can assess a limited scope.
 * No API in this service publishes figures to E8, RAG or a project.
 */
@ApplicationScoped
class PlatformSourceFiles {
    static final int MAX_BYTES=2*1024*1024;
    private static final String RULE="CF-OPEN-003";
    private static final String VIEW_SQL = """
      SELECT o.id,o.submission_id,o.organization_id,o.uploaded_by,o.license_id,
             o.license_version,o.file_name,o.file_format,o.sha256,o.bytes_count,
             o.state,o.issues,o.parsed_rows,o.uploaded_at,o.purged_at,
             s.source_id,s.edition,s.submitted_by,s.state,
             o.nonce,o.encrypted
      FROM app_platform_source_object o
      JOIN app_platform_source_submission s ON s.id=o.submission_id
      """;
    record Grant(UUID license, int decisionVersion, UUID organization) {}
    record Source(UUID id,String sourceId,String edition,UUID submittedBy,String state,
                  String declaredSha256,String kind,Integer referenceYear) {}
    record InternalFile(UUID id,UUID submission,UUID organization,UUID uploadedBy,UUID licenseId,
                        int licenseVersion,String name,String format,byte[] sha256,int bytes,
                        String state,List<String> issues,int parsedRows,OffsetDateTime uploadedAt,
                        OffsetDateTime purgedAt,String sourceId,String edition,UUID submittedBy,
                        String submissionState,byte[] nonce,byte[] ciphertext) {}
    record FileView(UUID id,UUID submissionId,String filename,String format,String sha256,
                    int bytesCount,String state,List<String> issues,int parsedRows,
                    OffsetDateTime uploadedAt,boolean licenseActive,boolean usableForE8) {}
    record Board(boolean canUpload,boolean canReview,List<FileView> files) {}
    record Decision(String outcome,String metric,Integer periodYear,String geography,String unit,
                    String evidenceLocator,String reason) {}
    record Assessment(UUID id,UUID objectId,String outcome,String methodology,
                      String edition,String scenario,String blockedCapability,String metric,
                      int periodYear,String geography,String unit,String evidenceLocator,
                      String reason,UUID reviewedBy,OffsetDateTime reviewedAt) {}

    private final PilotDatabase database;
    private final PlatformSourceReadiness readiness;
    private final PlatformSourceWork work;
    private final byte[] key;
    private final Set<UUID> reviewers;

    PlatformSourceFiles(PilotDatabase database,PlatformSourceReadiness readiness,
            PlatformSourceWork work,
            @ConfigProperty(name="carbonflow.platform.source-files.encryption-key") Optional<String> configuredKey,
            @ConfigProperty(name="carbonflow.platform.source-technical.reviewers") Optional<String> roster) {
        this.database=database;
        this.readiness=readiness;
        this.work=work;
        try {
            this.key=configuredKey.filter(x->!x.isBlank()).map(x->Base64.getUrlDecoder().decode(x)).orElse(null);
            if(key!=null && key.length!=32) throw new IllegalArgumentException("Chave AES de fontes inválida");
            this.reviewers=roster.map(value->Arrays.stream(value.split(","))
                    .map(String::strip).filter(x->!x.isEmpty()).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch(IllegalArgumentException ex) {
            throw new IllegalStateException("Configuração de criptografia/revisão de fontes inválida.");
        }
    }

    Board list(String token,UUID submission) {
        return database.transaction(connection->{
            var actor=readiness.requireViewer(connection,token);
            source(connection,submission);
            var organizations=connection.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try {
                List<InternalFile> results=query(connection,VIEW_SQL+"""
                    WHERE o.submission_id = ? AND o.organization_id = ANY(?)
                    ORDER BY o.uploaded_at DESC,o.id DESC LIMIT 20
                    """,PlatformSourceFiles::fileRow,submission,organizations);
                var views=results.stream().map(row->{
                    try {
                        return view(connection,row,actor.organizations());
                    } catch(SQLException exception) {throw new IllegalStateException(exception);}
                }).toList();
                ComplianceStaff.audit(connection,actor.id(),null,"VIEW_PLATFORM_SOURCE_OBJECTS");
                return new Board(work.canManage(actor.id()),reviewers.contains(actor.id()),views);
            } finally {
                organizations.free();
            }
        });
    }

    FileView upload(String token,UUID submission,String fileName,String format,byte[] original) {
        return database.transaction(connection->{
            var actor=work.requireManager(connection,token);
            requireKey();
            Source source=source(connection,submission);
            if(!"METADATA_ACCEPTED".equals(source.state()) || !"DATASET".equals(source.kind())) {
                throw invalid("submission","Somente metadados aceitos de um dataset permitem upload protegido.");
            }
            fileName=filename(fileName);
            format=format(format,fileName,original);
            if(original==null || original.length<16 || original.length>MAX_BYTES) {
                throw new PilotProblem(413,"ARQUIVO_FORA_DO_LIMITE","file",RULE,
                        "Selecione arquivo CSV/XLSX de 16 bytes a 2 MiB.");
            }
            byte[] sha=AccessSecrets.digest(HexFormat.of().formatHex(original));
            // AccessSecrets.digest(String) hashes text. The original file hash must hash raw bytes.
            sha=hash(original);
            String actual=HexFormat.of().formatHex(sha);
            if(source.declaredSha256()!=null &&
                    !MessageDigest.isEqual(actual.getBytes(StandardCharsets.US_ASCII),
                            source.declaredSha256().getBytes(StandardCharsets.US_ASCII))) {
                throw invalid("sha256","SHA-256 do arquivo diverge do original declarado na fonte.");
            }
            Grant grant=requireGrant(connection,actor.organizations(),source.sourceId(),
                    source.edition(),null,"STORE_ORIGINAL");
            var existing=first(connection,VIEW_SQL+"""
                WHERE o.submission_id=? AND o.organization_id=? AND o.sha256=?
                  AND o.purged_at IS NULL
                """,PlatformSourceFiles::fileRow,submission,grant.organization(),sha);
            if(existing!=null) return view(connection,existing,actor.organizations());
            Integer count=first(connection, """
                SELECT count(*) FROM app_platform_source_object
                WHERE submission_id=? AND organization_id=? AND purged_at IS NULL
                """,r->r.getInt(1),submission,grant.organization());
            if(count!=null && count>=5) throw new PilotProblem(409,"LIMITE_DE_ARQUIVOS","submission",
                    RULE,"Limite de cinco arquivos ativos para esta fonte e organização.");
            UUID id=UUID.randomUUID();
            byte[] nonce=AccessSecrets.randomBytes(12);
            byte[] encrypted=crypt(Cipher.ENCRYPT_MODE,grant.organization(),submission,id,sha,
                    nonce,original);
            UUID created=first(connection, """
                INSERT INTO app_platform_source_object
                    (id,submission_id,organization_id,uploaded_by,license_id,license_version,
                     file_name,file_format,sha256,bytes_count,nonce,encrypted)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """,r->r.getObject(1,UUID.class),id,submission,grant.organization(),actor.id(),
                grant.license(),grant.decisionVersion(),fileName,format,sha,original.length,nonce,encrypted);
            if(created==null) {
                var duplicate=first(connection,VIEW_SQL+"""
                    WHERE o.submission_id=? AND o.organization_id=? AND o.sha256=?
                      AND o.purged_at IS NULL
                    """,PlatformSourceFiles::fileRow,submission,grant.organization(),sha);
                if(duplicate!=null)return view(connection,duplicate,actor.organizations());
                throw new PilotProblem(409,"UPLOAD_SIMULTANEO","file",RULE,
                    "O conteúdo foi enviado por outro gestor. Atualize a consulta.");
            }
            ComplianceStaff.audit(connection,actor.id(),null,"UPLOAD_PLATFORM_SOURCE_OBJECT");
            var stored=file(connection,id,actor.organizations());
            return view(connection,stored,actor.organizations());
        });
    }

    FileView parse(String token,UUID id) {
        return database.transaction(connection->{
            var actor=work.requireManager(connection,token);
            requireKey();
            var stored=file(connection,id,actor.organizations());
            if(stored.purgedAt()!=null) throw forbidden("O conteúdo foi expurgado.");
            if(!"METADATA_ACCEPTED".equals(stored.submissionState())) {
                throw forbidden("Metadados não aceitos.");
            }
            requireGrant(connection,actor.organizations(),stored.sourceId(),stored.edition(),
                    stored.licenseId(),"EXTRACT_TEXT");
            if("APPROVED_FOR_SCOPE".equals(stored.state())) throw new PilotProblem(409,
                    "PARECER_JA_CONCLUIDO","file",RULE,"Dados já receberam parecer de escopo.");
            byte[] plain=crypt(Cipher.DECRYPT_MODE,stored.organization(),stored.submission(),
                    stored.id(),stored.sha256(),stored.nonce(),stored.ciphertext());
            Integer refYear=first(connection, """
                SELECT reference_year FROM app_platform_source_submission WHERE id=?
                """,r->(Integer)r.getObject(1),stored.submission());
            var parsed=TabularCandidateParser.parse(plain,stored.format(),refYear);
            update(connection,"DELETE FROM app_platform_source_candidate WHERE object_id=?",id);
            for(var row:parsed.candidates()){
                update(connection, """
                    INSERT INTO app_platform_source_candidate
                    (object_id,row_index,record_key,metric,period_year,geography,unit,value_numeric,locator)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,id,row.row(),row.recordKey(),row.metric(),row.year(),
                    row.geography(),row.unit(),row.value(),row.locator());
            }
            var issues=connection.createArrayOf("text",parsed.issues().toArray(String[]::new));
            try {
                update(connection, """
                    UPDATE app_platform_source_object SET
                      state=?,issues=?,parsed_rows=?,parser_version=?,parsed_at=statement_timestamp()
                    WHERE id=?
                    """,parsed.valid()?"NORMALIZED_CANDIDATE":"SCHEMA_REJECTED",
                    issues,parsed.candidates().size(),TabularCandidateParser.VERSION,id);
            } finally {
                issues.free();
            }
            ComplianceStaff.audit(connection,actor.id(),null,"PARSE_PLATFORM_SOURCE_OBJECT");
            return view(connection,file(connection,id,actor.organizations()),actor.organizations());
        });
    }

    Assessment assess(String token,UUID id,Decision decision) {
        return database.transaction(connection->{
            var actor=readiness.requireViewer(connection,token);
            if(!reviewers.contains(actor.id())) throw forbidden("Revisão técnica exige papel específico.");
            if(decision==null || decision.outcome()==null ||
                    !Set.of("APPROVED_FOR_SCOPE","REJECTED_FOR_SCOPE","NEEDS_INFORMATION")
                    .contains(decision.outcome())) throw invalid("outcome","Decisão técnica inválida.");
            var stored=file(connection,id,actor.organizations());
            if(stored.purgedAt()!=null) throw forbidden("Objeto expurgado.");
            if(actor.id().equals(stored.uploadedBy()) || actor.id().equals(stored.submittedBy())) {
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                        "O autor dos metadados ou upload não revisa os próprios dados.");
            }
            if(!"NORMALIZED_CANDIDATE".equals(stored.state())) {
                throw new PilotProblem(409,"CANDIDATO_NAO_VALIDADO","file",RULE,
                        "Conclua a análise tabular sem pendências antes do parecer.");
            }
            requireGrant(connection,actor.organizations(),stored.sourceId(),stored.edition(),
                    stored.licenseId(),"EXTRACT_TEXT");
            requireGrant(connection,actor.organizations(),stored.sourceId(),stored.edition(),
                    stored.licenseId(),"STORE_ORIGINAL");
            var scope=first(connection, """
                SELECT n.methodology,n.methodology_edition,n.scenario,n.blocked_capability
                FROM app_platform_source_submission s
                JOIN app_methodology_source_need n ON n.code=s.need_code
                WHERE s.id=?
                """,r->new String[]{r.getString(1),r.getString(2),r.getString(3),r.getString(4)},
                stored.submission());
            if(scope==null)throw forbidden("Escopo metodológico indisponível.");
            String metric=required(decision.metric(),"metric",1,80),
                   geography=required(decision.geography(),"geography",2,64).toUpperCase(),
                   unit=required(decision.unit(),"unit",1,40),
                   evidence=required(decision.evidenceLocator(),"evidenceLocator",5,300),
                   reason=required(decision.reason(),"reason",10,2000);
            if(!metric.matches("[A-Za-z0-9._-]{1,80}") ||
               !geography.matches("[A-Z][A-Z0-9._-]{1,63}") ||
               !TabularCandidateParser.UNITS.contains(unit) ||
               decision.periodYear()==null ||
               decision.periodYear()<1900 || decision.periodYear()>2100) {
                throw invalid("dimensions","Escopo dimensional inválido.");
            }
            Integer count=first(connection, """
                SELECT count(*) FROM app_platform_source_candidate
                WHERE object_id=? AND metric=? AND period_year=? AND geography=? AND unit=?
                """,r->r.getInt(1),id,metric,decision.periodYear(),geography,unit);
            if(count==null || count==0)throw invalid("dimensions",
                    "Não existe candidato com as dimensões declaradas.");
            var previous=first(connection, """
                SELECT id FROM app_platform_source_assessment
                WHERE object_id=? AND methodology=? AND methodology_edition=? AND scenario=?
                  AND blocked_capability=? AND metric=? AND period_year=? AND geography=? AND unit=?
                """,r->r.getObject(1,UUID.class),id,scope[0],scope[1],scope[2],scope[3],
                    metric,decision.periodYear(),geography,unit);
            if(previous!=null)throw new PilotProblem(409,"ESCOPO_JA_AVALIADO","dimensions",RULE,
                    "Um parecer já foi registrado para essas dimensões. Use uma nova edição de dados.");
            UUID assessmentId=UUID.randomUUID();
            update(connection, """
                INSERT INTO app_platform_source_assessment
                (id,object_id,outcome,methodology,methodology_edition,scenario,blocked_capability,
                 metric,period_year,geography,unit,evidence_locator,reason,reviewed_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,assessmentId,id,decision.outcome(),scope[0],scope[1],scope[2],scope[3],
                metric,decision.periodYear(),geography,unit,evidence,reason,actor.id());
            update(connection, """
                UPDATE app_platform_source_object SET state=? WHERE id=?
                """,switch(decision.outcome()){
                    case "APPROVED_FOR_SCOPE"->"APPROVED_FOR_SCOPE";
                    case "REJECTED_FOR_SCOPE"->"REJECTED_FOR_SCOPE";
                    default->"TECHNICAL_REVIEW_REQUIRED";
                },id);
            ComplianceStaff.audit(connection,actor.id(),null,"ASSESS_PLATFORM_SOURCE_OBJECT");
            return new Assessment(assessmentId,id,decision.outcome(),scope[0],scope[1],
                    scope[2],scope[3],metric,decision.periodYear(),geography,unit,evidence,
                    reason,actor.id(),OffsetDateTime.now());
        });
    }

    private FileView view(Connection connection,InternalFile file,Set<UUID> organizations)
        throws SQLException {
        boolean active=file.purgedAt()==null && granted(connection,organizations,
                file.sourceId(),file.edition(),file.licenseId(),"STORE_ORIGINAL")!=null;
        return new FileView(file.id(),file.submission(),file.name(),file.format(),
                HexFormat.of().formatHex(file.sha256()),file.bytes(),
                active?file.state():"SUSPENDED",active?file.issues():List.of(),active?file.parsedRows():0,
                file.uploadedAt(),active,false);
    }

    private static InternalFile fileRow(java.sql.ResultSet r) throws SQLException {
        String[] issues=(String[])r.getArray(12).getArray();
        return new InternalFile(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getObject(5,UUID.class),
            r.getInt(6),r.getString(7),r.getString(8),r.getBytes(9),r.getInt(10),
            r.getString(11),List.of(issues),r.getInt(13),r.getObject(14,OffsetDateTime.class),
            r.getObject(15,OffsetDateTime.class),r.getString(16),r.getString(17),
            r.getObject(18,UUID.class),r.getString(19),r.getBytes(20),r.getBytes(21));
    }

    private InternalFile file(Connection connection,UUID id,Set<UUID> organizations)
        throws SQLException {
        var orgArray=connection.createArrayOf("uuid",organizations.toArray(UUID[]::new));
        try {
            var result=first(connection,VIEW_SQL+"""
                WHERE o.id=? AND o.organization_id=ANY(?)
                """,PlatformSourceFiles::fileRow,id,orgArray);
            if(result==null)throw forbidden("Arquivo não disponível para esta organização.");
            return result;
        } finally {orgArray.free();}
    }

    private static Source source(Connection connection,UUID submission)throws SQLException {
        Source source=first(connection, """
            SELECT s.id,s.source_id,s.edition,s.submitted_by,s.state,s.declared_original_sha256,
                   n.kind,s.reference_year
            FROM app_platform_source_submission s
            JOIN app_methodology_source_need n ON n.code=s.need_code
            WHERE s.id=?
            """,r->new Source(r.getObject(1,UUID.class),r.getString(2),r.getString(3),
                r.getObject(4,UUID.class),r.getString(5),r.getString(6),r.getString(7),
                (Integer)r.getObject(8)),submission);
        if(source==null)throw forbidden("Submissão indisponível.");
        return source;
    }

    private Grant requireGrant(Connection connection,Set<UUID> organizations,String source,
            String edition,UUID license,String operation) throws SQLException {
        var result=granted(connection,organizations,source,edition,license,operation);
        if(result==null)throw new PilotProblem(403,"DIREITOS_NAO_AUTORIZADOS","license",RULE,
                "A operação exige licença vigente e independente para esta organização e finalidade.");
        return result;
    }

    private Grant granted(Connection connection,Set<UUID> organizations,String source,
            String edition,UUID bound,String operation) throws SQLException {
        var array=connection.createArrayOf("uuid",organizations.toArray(UUID[]::new));
        try {
            return first(connection, """
                SELECT l.id,d.version,l.organization_id
                FROM app_content_license l
                JOIN LATERAL (
                    SELECT version,outcome,decided_by,decided_at FROM app_content_license_decision
                    WHERE license_id=l.id ORDER BY version DESC LIMIT 1
                ) d ON true
                JOIN app_content_license_capability cap ON cap.license_id=l.id
                    AND cap.decision_version=d.version
                WHERE l.source_id=? AND l.edition=?
                  AND (?::uuid IS NULL OR l.id=?)
                  AND l.organization_id=ANY(?) AND l.project_id IS NULL AND l.actor_id IS NULL
                  AND l.environment='SANDBOX' AND l.purpose='AUTHORIZED_PROCESSING'
                  AND l.provider IS NULL AND cap.operation=?
                  AND l.proof_sha256 IS NOT NULL AND l.submitted_by IS NOT NULL
                  AND d.decided_by IS NOT NULL AND d.decided_by<>l.submitted_by
                  AND d.outcome='APPROVED' AND d.decided_at<=statement_timestamp()
                  AND l.starts_at<=statement_timestamp()
                  AND (l.ends_at IS NULL OR l.ends_at>statement_timestamp())
                ORDER BY l.id LIMIT 1
                """,r->new Grant(r.getObject(1,UUID.class),r.getInt(2),
                    r.getObject(3,UUID.class)),source,edition,bound,bound,array,operation);
        } finally {array.free();}
    }

    private byte[] crypt(int mode,UUID organization,UUID submission,UUID object,byte[] digest,
            byte[] nonce,byte[] data) {
        requireKey();
        try {
            var cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(mode,new SecretKeySpec(key,"AES"),new GCMParameterSpec(128,nonce));
            cipher.updateAAD((organization+"/"+submission+"/"+object+"/"+
                HexFormat.of().formatHex(digest)).getBytes(StandardCharsets.UTF_8));
            return cipher.doFinal(data);
        } catch(GeneralSecurityException ex){
            throw new IllegalStateException("Falha ao proteger conteúdo de fonte",ex);
        }
    }

    private void requireKey() {
        if(key==null)throw new PilotProblem(503,"ARMAZENAMENTO_INDISPONIVEL","file",RULE,
                "Chave de armazenamento de fontes não configurada.");
    }

    private static String filename(String name) {
        String clean=required(name,"filename",5,100);
        if(!clean.matches("[A-Za-z0-9][A-Za-z0-9._-]{3,99}") ||
            clean.contains(".."))throw invalid("filename","Nome de arquivo inválido.");
        return clean;
    }

    private static String format(String format,String name,byte[] data) {
        if(!"CSV".equals(format)&&!"XLSX".equals(format))throw invalid("format",
                "Somente CSV e XLSX são aceitos.");
        if(!name.toLowerCase().endsWith("."+format.toLowerCase()))throw invalid("filename",
                "Extensão não corresponde ao formato declarado.");
        if(data!=null && "XLSX".equals(format) &&
             (data.length<4 || data[0]!='P' || data[1]!='K' || data[2]!=3 || data[3]!=4)){
            throw invalid("file","XLSX não corresponde à assinatura OOXML.");
        }
        if(data!=null && "CSV".equals(format) && data.length>=2 &&
                (data[0]==0 || data[1]==0))throw invalid("file","CSV binário não permitido.");
        return format;
    }

    private static String required(String value,String field,int min,int max) {
        if(value==null || value.strip().length()<min || value.strip().length()>max)
            throw invalid(field,"Campo obrigatório ou fora dos limites.");
        return value.strip();
    }
    private static PilotProblem invalid(String field,String message) {
        return PilotProblem.invalid("DADOS_TABULARES_INVALIDOS",field,RULE,message);
    }
    private static PilotProblem forbidden(String message){
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","file",RULE,message);
    }
    private static byte[] hash(byte[] bytes) {
        try {return MessageDigest.getInstance("SHA-256").digest(bytes);}
        catch(GeneralSecurityException error){throw new IllegalStateException(error);}
    }
}
