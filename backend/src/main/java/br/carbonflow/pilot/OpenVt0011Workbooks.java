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
import java.time.OffsetDateTime;
import java.time.Year;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Preparatory and immutable VT0011/TOOL07 Case 2 evidence workbook.
 * A provided OM estimate cannot be treated as a verified OM calculation,
 * nor can its tCO2/MWh preview replace a CO2e hour-specific EFel series.
 */
@ApplicationScoped
class OpenVt0011Workbooks {
    private static final String RULE="CF-OPEN-007";
    private static final Set<String> OM_METHODS=Set.of(
        "SIMPLE_OM","SIMPLE_ADJUSTED_OM","DISPATCH_DATA_ANALYSIS_OM","AVERAGE_OM");

    record Input(UUID efelCandidateId,String omMethod,String omVintageMode,
                 Integer omDataYear,BigDecimal omFactorTco2PerMwh,
                 String omSourceReference,String omDatasetSha256,
                 String omMethodJustification,String gridBoundaryEvidence,
                 String sampleSelectionEvidence,String importTreatmentEvidence,
                 String vintageEvidence,String methodologyNote) {}
    record Decision(String outcome,String rationale,String evidenceReference) {}
    record Review(int version,String outcome,String rationale,String evidenceReference,
                  UUID reviewerId,OffsetDateTime reviewedAt) {}
    record View(UUID id,String period,int version,UUID efelCandidateId,
                int efelReviewVersion,String sourceSha256,String vt0011Version,
                String tool07Version,String gridSystem,String caseCode,
                String omMethod,String omVintageMode,int omDataYear,
                String omSourceReference,String omDatasetSha256,
                String omMethodJustification,String gridBoundaryEvidence,
                String sampleSelectionEvidence,String importTreatmentEvidence,
                String vintageEvidence,String methodologyNote,
                String contentSha256,UUID authoredBy,OffsetDateTime authoredAt,
                OpenVt0011Case2Math.Preview preview,Review review,
                String state,boolean stale,boolean usableForE8,List<String> gates) {}
    private record Source(UUID id,String period,String sourceHash,
                          UUID author,int reviewVersion) {}
    private record Workbook(UUID id,String period,int version,UUID source,int sourceReview,
                            String sourceSha,String omMethod,String vintageMode,int year,
                            BigDecimal omFactor,String omSource,String omDataset,
                            String methodJustification,String boundary,String sample,
                            String imports,String vintage,String note,String sha,
                            UUID author,OffsetDateTime at) {}
    private record Command(String project,UUID workbook,String action,byte[] fingerprint) {}
    private record Normal(String method,String vintageMode,int year,BigDecimal om,
                          String omSource,String omDataset,String reason,String boundary,
                          String sample,String imports,String vintage,String note,
                          String fingerprint) {}

    private final DynamicProjectScope scope;
    OpenVt0011Workbooks(DynamicProjectScope scope){this.scope=scope;}

    List<View> list(String cookie,String project,String period,UUID sourceId){
        return scope.read(cookie,project,"VIEW_OPEN_VT0011_WORKBOOK",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            Source source=source(c,project,sourceId);
            if(source==null || !code.equals(source.period()))throw unavailable();
            List<Workbook> stored=query(c,WORKBOOK_SQL+"""
                WHERE project_id=? AND efel_candidate_id=?
                ORDER BY version DESC LIMIT 20
                """,OpenVt0011Workbooks::row,project,sourceId);
            var result=new ArrayList<View>();
            for(Workbook item:stored)result.add(view(c,project,item));
            return List.copyOf(result);
        });
    }

    View submit(String cookie,String project,String period,String match,String key,Input submitted){
        return scope.write(cookie,project,"SUBMIT_OPEN_VT0011_WORKBOOK",(c,ctx)->{
            if(!ctx.owner() && !"PREP".equals(ctx.role()))throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw unavailable();
            if(submitted==null || submitted.efelCandidateId()==null)
                throw bad("efelCandidateId","Selecione uma série EFel existente.");
            Source source=source(c,project,submitted.efelCandidateId());
            if(source==null || !code.equals(source.period()))throw unavailable();
            if(!current(c,project,source))
                throw conflict("A série EFel ou sua conferência interna está ausente ou obsoleta.");
            Normal normalized=normalize(submitted,source,project);
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            byte[] fingerprint=AccessSecrets.digest("VT0011-WORKBOOK|"+project+"|"+
                source.id()+"|"+expected+"|"+normalized.fingerprint());
            Command previous=command(c,ctx.actor().id(),command);
            if(previous!=null){
                if(!project.equals(previous.project()) || !"SUBMIT".equals(previous.action()) ||
                    !MessageDigest.isEqual(fingerprint,previous.fingerprint()))
                    throw conflict("A chave já identifica outro caderno.");
                return view(c,project,require(c,project,previous.workbook()));
            }
            Workbook latest=latest(c,project,source.id());
            PilotCommands.requireVersion(expected,latest==null?0:latest.version());
            if(expected>=100000000)throw bad("version","Limite de revisões atingido.");
            if(latest!=null && latest.sha().equals(normalized.fingerprint()))
                throw conflict("Caderno idêntico à revisão anterior; não duplique o histórico.");
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_vt0011_case2_workbook
                 (id,project_id,organization_id,environment,period_code,
                  efel_candidate_id,efel_review_version,version,
                  om_method,om_vintage_mode,om_data_year,om_factor_tco2_per_mwh,
                  om_source_reference,om_dataset_sha256_claimed,
                  om_method_justification,grid_boundary_evidence,
                  sample_selection_evidence,import_treatment_evidence,
                  vintage_evidence,methodology_note,content_sha256,authored_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),code,source.id(),
                source.reviewVersion(),expected+1,normalized.method(),
                normalized.vintageMode(),normalized.year(),normalized.om(),
                normalized.omSource(),HexFormat.of().parseHex(normalized.omDataset()),
                normalized.reason(),normalized.boundary(),normalized.sample(),
                normalized.imports(),normalized.vintage(),normalized.note(),
                HexFormat.of().parseHex(normalized.fingerprint()),ctx.actor().id());
            update(c,"""
                INSERT INTO app_open_vt0011_case2_command
                (actor_id,command_key,project_id,workbook_id,action,fingerprint)
                VALUES (?,?,?,?,?,?)
                """,ctx.actor().id(),command,project,id,"SUBMIT",fingerprint);
            return view(c,project,require(c,project,id));
        });
    }

    View review(String cookie,String project,String period,UUID workbookId,
                String match,String key,Decision submitted){
        return scope.write(cookie,project,"REVIEW_OPEN_VT0011_WORKBOOK",(c,ctx)->{
            if(ctx.owner() || !"REV".equals(ctx.role()))throw unavailable();
            String code=OpenMonitoringDossiers.code(period);
            Workbook workbook=require(c,project,workbookId);
            if(!code.equals(workbook.period()))throw unavailable();
            Source source=source(c,project,workbook.source());
            if(source==null || ctx.actor().id().equals(workbook.author()) ||
                ctx.actor().id().equals(source.author()))
                throw new PilotProblem(403,"VT0011_REVIEW_NOT_INDEPENDENT","reviewer",
                    RULE,"O revisor precisa ser independente dos autores do caderno e do EFel.");
            if(submitted==null || submitted.outcome()==null ||
                !Set.of("CHECKED_INTERNAL","RETURNED").contains(submitted.outcome()))
                throw bad("outcome","Informe CHECKED_INTERNAL ou RETURNED.");
            String reason=clean(submitted.rationale(),30,2000,"rationale");
            String evidence=clean(submitted.evidenceReference(),15,600,"evidenceReference");
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            byte[] fingerprint=AccessSecrets.digest("VT0011-REVIEW|"+project+"|"+
                workbookId+"|"+expected+"|"+submitted.outcome()+"|"+reason+"|"+evidence);
            Command previous=command(c,ctx.actor().id(),command);
            if(previous!=null){
                if(!project.equals(previous.project()) || !"REVIEW".equals(previous.action()) ||
                    !workbookId.equals(previous.workbook()) ||
                    !MessageDigest.isEqual(fingerprint,previous.fingerprint()))
                    throw conflict("Chave de idempotência já utilizada.");
                return view(c,project,workbook);
            }
            Workbook latest=latest(c,project,source.id());
            if(!current(c,project,source) || latest==null ||
                !latest.id().equals(workbookId) ||
                !Set.of("PENDING_INTERNAL_REVIEW_NOT_ADMITTED",
                    "RETURNED_NOT_ADMITTED",
                    "CHECKED_INTERNAL_NOT_ADMITTED").contains(view(c,project,workbook).state()))
                throw conflict("Caderno ou fonte obsoletos. Não é possível revisar.");
            Review old=latestReview(c,project,workbookId);
            PilotCommands.requireVersion(expected,old==null?0:old.version());
            if(expected>=100000000)throw bad("version","Limite de pareceres.");
            update(c,"""
                INSERT INTO app_open_vt0011_case2_review
                  (project_id,workbook_id,version,outcome,rationale,evidence_reference,reviewer_id)
                VALUES (?,?,?,?,?,?,?)
                """,project,workbookId,expected+1,submitted.outcome(),
                reason,evidence,ctx.actor().id());
            update(c,"""
                INSERT INTO app_open_vt0011_case2_command
                  (actor_id,command_key,project_id,workbook_id,action,fingerprint,review_version)
                VALUES (?,?,?,?,?,?,?)
                """,ctx.actor().id(),command,project,workbookId,"REVIEW",
                fingerprint,expected+1);
            return view(c,project,workbook);
        });
    }

    static String state(Connection c,String project,String period) throws SQLException {
        UUID source=first(c,"""
            SELECT id FROM app_open_efel_hourly_candidate
            WHERE project_id=? AND period_code=?
            ORDER BY submitted_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,period);
        if(source==null)return "VT0011_SOURCE_MISSING";
        Workbook latest=latest(c,project,source);
        return latest==null?"VT0011_WORKBOOK_MISSING":view(c,project,latest).state();
    }

    private static boolean current(Connection c,String project,Source source)
        throws SQLException {
        UUID latestSource=first(c,"""
            SELECT id FROM app_open_efel_hourly_candidate
            WHERE project_id=? AND period_code=?
            ORDER BY submitted_at DESC,id DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),project,source.period());
        return source.id().equals(latestSource) &&
            "CHECKED_INTERNAL_NOT_ADMITTED".equals(
                OpenEfelCandidates.state(c,project,source.period()));
    }

    private static View view(Connection c,String project,Workbook item) throws SQLException {
        Source source=source(c,project,item.source());
        Workbook latest=latest(c,project,item.source());
        boolean superseded=latest!=null && latest.version()!=item.version();
        boolean stale=source==null || !current(c,project,source) ||
            source.reviewVersion()!=item.sourceReview() ||
            !source.sourceHash().equals(item.sourceSha());
        var gates=new ArrayList<String>();
        if(stale)gates.add("SOURCE_OR_REVIEW_STALE");
        if(superseded)gates.add("WORKBOOK_VERSION_SUPERSEDED");
        Review review=latestReview(c,project,item.id());
        if(review==null)gates.add("INDEPENDENT_INTERNAL_REVIEW_PENDING");
        if(review!=null && "RETURNED".equals(review.outcome()))
            gates.add("INTERNAL_REVIEW_RETURNED");
        // Case 2 algebra alone does not calculate the underlying OM from
        // TOOL07 plant data, nor establish a CO2 -> CO2e/hourly EFel bridge.
        gates.add("TOOL07_OM_CALCULATION_NOT_VERIFIED");
        gates.add("SOURCE_DATASET_HASH_NOT_AUTHENTICATED");
        gates.add("CO2_TO_CO2E_AND_HOURLY_EFEL_BRIDGE_MISSING");
        gates.add("NORMATIVE_ADMISSION_AND_E8_BLOCKED");
        String state=stale?"STALE_NOT_ADMITTED":
            superseded?"SUPERSEDED_NOT_ADMITTED":
            review==null?"PENDING_INTERNAL_REVIEW_NOT_ADMITTED":
            "RETURNED".equals(review.outcome())?"RETURNED_NOT_ADMITTED":
            "CHECKED_INTERNAL_NOT_ADMITTED";
        return new View(item.id(),item.period(),item.version(),item.source(),
            item.sourceReview(),item.sourceSha(),"1.0","7.0","SIN",
            "CASE_2_INCREASE_GRID_CONSUMPTION",item.omMethod(),item.vintageMode(),
            item.year(),item.omSource(),item.omDataset(),item.methodJustification(),
            item.boundary(),item.sample(),item.imports(),item.vintage(),item.note(),
            item.sha(),item.author(),item.at(),
            OpenVt0011Case2Math.illustrate(item.omFactor()),review,
            state,stale,false,List.copyOf(gates));
    }

    private static Normal normalize(Input i,Source source,String project){
        if(i.omMethod()==null || !OM_METHODS.contains(i.omMethod()))
            throw bad("omMethod","Selecione um dos quatro métodos OM do TOOL07.");
        if(i.omVintageMode()==null ||
           !Set.of("EX_ANTE","EX_POST").contains(i.omVintageMode()))
            throw bad("omVintageMode","Defina explicitamente o tipo de vintage.");
        if(i.omDataYear()==null || i.omDataYear()<1990 || i.omDataYear()>Year.now().getValue())
            throw bad("omDataYear","Ano de origem de OM ausente ou posterior ao ano atual.");
        try{OpenVt0011Case2Math.illustrate(i.omFactorTco2PerMwh());}
        catch(IllegalArgumentException error){throw bad("omFactorTco2PerMwh",error.getMessage());}
        String omSource=clean(i.omSourceReference(),15,600,"omSourceReference");
        String dataset=clean(i.omDatasetSha256(),64,64,"omDatasetSha256").toLowerCase();
        if(!dataset.matches("[0-9a-f]{64}"))
            throw bad("omDatasetSha256","SHA-256 declarado deve conter 64 caracteres hexadecimais.");
        String reason=clean(i.omMethodJustification(),30,2000,"omMethodJustification");
        String boundary=clean(i.gridBoundaryEvidence(),15,600,"gridBoundaryEvidence");
        String sample=clean(i.sampleSelectionEvidence(),15,600,"sampleSelectionEvidence");
        String imports=clean(i.importTreatmentEvidence(),15,600,"importTreatmentEvidence");
        String vintage=clean(i.vintageEvidence(),15,600,"vintageEvidence");
        String note=clean(i.methodologyNote(),30,2000,"methodologyNote");
        String canonical=String.join("|","CF-OPEN-VT0011-CASE2-V1",project,
            source.id().toString(),source.sourceHash(),Integer.toString(source.reviewVersion()),
            i.omMethod(),i.omVintageMode(),i.omDataYear().toString(),
            i.omFactorTco2PerMwh().stripTrailingZeros().toPlainString(),
            omSource,dataset,reason,boundary,sample,imports,vintage,note);
        return new Normal(i.omMethod(),i.omVintageMode(),i.omDataYear(),
            i.omFactorTco2PerMwh(),omSource,dataset,reason,boundary,sample,
            imports,vintage,note,digest(canonical));
    }

    private static String digest(String value){
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException error){throw new IllegalStateException(error);}
    }
    private static String clean(String s,int min,int max,String field){
        if(s==null || s.strip().length()<min || s.strip().length()>max ||
           s.codePoints().anyMatch(Character::isISOControl))
            throw bad(field,"Texto ou referência com tamanho/formato inválido.");
        return s.strip();
    }

    private static Source source(Connection c,String project,UUID id) throws SQLException {
        return first(c,"""
            SELECT e.id,e.period_code,encode(e.content_sha256,'hex') AS sha,
              e.submitted_by,
              coalesce((SELECT max(version) FROM app_open_efel_hourly_review r
                WHERE r.project_id=e.project_id AND r.candidate_id=e.id),0) AS review_version
            FROM app_open_efel_hourly_candidate e WHERE e.project_id=? AND e.id=?
            """,r->new Source(r.getObject("id",UUID.class),r.getString("period_code"),
                r.getString("sha"),r.getObject("submitted_by",UUID.class),
                r.getInt("review_version")),project,id);
    }
    private static final String WORKBOOK_SQL="""
        SELECT id,period_code,version,efel_candidate_id,efel_review_version,
          encode(efel_source_sha256,'hex') AS source_sha,
          om_method,om_vintage_mode,om_data_year,om_factor_tco2_per_mwh,
          om_source_reference,encode(om_dataset_sha256_claimed,'hex') AS dataset,
          om_method_justification,grid_boundary_evidence,
          sample_selection_evidence,import_treatment_evidence,vintage_evidence,
          methodology_note,encode(content_sha256,'hex') AS sha,authored_by,authored_at
        FROM app_open_vt0011_case2_workbook
        """;
    private static Workbook row(java.sql.ResultSet r) throws SQLException {
        return new Workbook(r.getObject("id",UUID.class),r.getString("period_code"),
            r.getInt("version"),r.getObject("efel_candidate_id",UUID.class),
            r.getInt("efel_review_version"),r.getString("source_sha"),
            r.getString("om_method"),r.getString("om_vintage_mode"),r.getInt("om_data_year"),
            r.getBigDecimal("om_factor_tco2_per_mwh"),r.getString("om_source_reference"),
            r.getString("dataset"),r.getString("om_method_justification"),
            r.getString("grid_boundary_evidence"),r.getString("sample_selection_evidence"),
            r.getString("import_treatment_evidence"),r.getString("vintage_evidence"),
            r.getString("methodology_note"),r.getString("sha"),
            r.getObject("authored_by",UUID.class),
            r.getObject("authored_at",OffsetDateTime.class));
    }
    private static Workbook latest(Connection c,String project,UUID efel) throws SQLException {
        return first(c,WORKBOOK_SQL+"""
            WHERE project_id=? AND efel_candidate_id=? ORDER BY version DESC LIMIT 1
            """,OpenVt0011Workbooks::row,project,efel);
    }
    private static Workbook require(Connection c,String project,UUID id) throws SQLException {
        Workbook found=first(c,WORKBOOK_SQL+"""
            WHERE project_id=? AND id=?
            """,OpenVt0011Workbooks::row,project,id);
        if(found==null)throw unavailable();
        return found;
    }
    private static Review latestReview(Connection c,String project,UUID id)throws SQLException {
        return first(c,"""
            SELECT version,outcome,rationale,evidence_reference,reviewer_id,reviewed_at
            FROM app_open_vt0011_case2_review
            WHERE project_id=? AND workbook_id=? ORDER BY version DESC LIMIT 1
            """,r->new Review(r.getInt("version"),r.getString("outcome"),
                r.getString("rationale"),r.getString("evidence_reference"),
                r.getObject("reviewer_id",UUID.class),
                r.getObject("reviewed_at",OffsetDateTime.class)),project,id);
    }
    private static Command command(Connection c,UUID actor,String key)throws SQLException {
        return first(c,"""
            SELECT project_id,workbook_id,action,fingerprint
            FROM app_open_vt0011_case2_command
            WHERE actor_id=? AND command_key=?
            """,r->new Command(r.getString(1),r.getObject(2,UUID.class),
                r.getString(3),r.getBytes(4)),actor,key);
    }
    private static PilotProblem unavailable(){
        return new PilotProblem(404,"VT0011_WORKBOOK_NOT_VISIBLE","workbook",RULE,
            "Caderno indisponível.");
    }
    private static PilotProblem conflict(String reason){
        return new PilotProblem(409,"VT0011_WORKBOOK_BLOCKED","workbook",RULE,reason);
    }
    private static PilotProblem bad(String field,String reason){
        return PilotProblem.invalid("VT0011_WORKBOOK_INVALID",field,RULE,reason);
    }
}
