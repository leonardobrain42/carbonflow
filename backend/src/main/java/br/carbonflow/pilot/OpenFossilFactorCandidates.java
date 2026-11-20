package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * #96, first REAL-SOURCE but PREPARATORY-only EFfuel workbench.
 * Numeric values must be extracted from a licensed, independently reviewed
 * source row; managers cannot invent or override them via JSON.
 * The review is an INTERNAL opinion, never source admission, a project binding
 * or permission to execute VM0038 Eq. 1.
 */
@ApplicationScoped
class OpenFossilFactorCandidates {
    static final String RULE="CF-OPEN-007";
    static final String SCENARIO="OPEN_LDV_DCFC_SIN_BR";
    static final String METRIC="EF_FUEL_GASOLINE";
    static final String PARAMETER="EF_FUEL";
    static final String GAS="CO2e";
    static final String FUEL="GASOLINE";

    record Source(UUID id,UUID organizationId,String edition,int year,String sha256) {}
    record Row(int rowIndex,String recordKey,int year,String geography,String unit,
               BigDecimal value,String locator) {}
    record Candidate(UUID id,UUID organizationId,UUID sourceObjectId,UUID sourceLicenseId,
        int sourceRow,String sourceSha256,String sourceLocator,
        int periodYear,String fuel,String gas,String originalUnit,BigDecimal originalValue,
        String normalizedUnit,BigDecimal normalizedValue,String conversionFormula,
        String status,String reviewOutcome,String reviewRule,String reviewEvidence,
        String reviewReason,boolean rightsActive,boolean usableForE8) {}
    record Board(UUID actorId,boolean canManage,boolean canReview,
                 List<Source> sources,List<Candidate> candidates) {}
    record Register(UUID sourceObjectId,Integer rowIndex) {}
    record Review(String outcome,String ruleReference,String evidenceLocator,String reason) {}
    private record Origin(UUID object,UUID organization,UUID license,
       UUID uploadedBy,UUID submittedBy,String sourceId,String edition,
       String objectState,String metadataState,String sha,
       int row,String key,int year,String geography,String unit,
       BigDecimal value,String locator,String assessmentOutcome,
       UUID initialReviewer) {}
    private record Stored(UUID id,UUID organization,UUID source,UUID license,
       int row,String sha,String locator,int year,String fuel,String gas,
       String unit,BigDecimal original,String target,BigDecimal normalized,
       String formula,String status,UUID author,UUID uploader,UUID submitter,
       String sourceId,String edition,String reviewOutcome,String reviewRule,
       String reviewEvidence,String reviewReason) {}

    private final PilotDatabase database;
    private final PlatformSourceReadiness readiness;
    private final PlatformSourceWork managers;
    private final PlatformSourceFiles rights;
    private final Set<UUID> reviewers;

    OpenFossilFactorCandidates(PilotDatabase database,PlatformSourceReadiness readiness,
        PlatformSourceWork managers,PlatformSourceFiles rights,
        @ConfigProperty(name="carbonflow.platform.source-technical.reviewers")
        Optional<String> reviewerRoster){
        this.database=database;
        this.readiness=readiness;
        this.managers=managers;
        this.rights=rights;
        try{
            reviewers=reviewerRoster.map(roster->Arrays.stream(roster.split(","))
                .map(String::strip).filter(x->!x.isBlank())
                .map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
                .orElse(Set.of());
        }catch(IllegalArgumentException ex){
            throw new IllegalStateException("Revisores de fatores fósseis inválidos.",ex);
        }
    }

    Board board(String cookie){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,cookie);
            var array=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try{
                var sources=query(c,"""
                    SELECT DISTINCT o.id,o.organization_id,s.edition,
                      s.reference_year,encode(o.sha256,'hex'),o.license_id,
                      s.source_id
                    FROM app_platform_source_object o
                    JOIN app_platform_source_submission s ON s.id=o.submission_id
                    JOIN app_methodology_source_need n ON n.code=s.need_code
                    JOIN app_platform_source_assessment a ON a.object_id=o.id
                    WHERE s.need_code='OPEN-FOSSIL-EF' AND s.state='METADATA_ACCEPTED'
                      AND o.state='APPROVED_FOR_SCOPE' AND o.purged_at IS NULL
                      AND o.organization_id=ANY(?)
                      AND n.methodology='VM0038' AND n.methodology_edition='1.1'
                      AND n.scenario=? AND n.blocked_capability='BASELINE_BE'
                      AND a.methodology='VM0038' AND a.methodology_edition='1.1'
                      AND a.scenario=? AND a.blocked_capability='BASELINE_BE'
                      AND a.outcome='APPROVED_FOR_SCOPE'
                      AND a.metric=? AND a.geography='BR'
                    ORDER BY o.id LIMIT 80
                    """,r->new Object[]{r.getObject(1,UUID.class),
                       r.getObject(2,UUID.class),r.getString(3),r.getObject(4,Integer.class),
                       r.getString(5),r.getObject(6,UUID.class),r.getString(7)},
                       array,SCENARIO,SCENARIO,METRIC);
                var allowedSources=new java.util.ArrayList<Source>();
                for(Object[] row:sources){
                    UUID org=(UUID)row[1],license=(UUID)row[5];
                    if(canUse(c,actor.organizations(),(String)row[6],(String)row[2],license,
                              "EXTRACT_TEXT") &&
                       canUse(c,actor.organizations(),(String)row[6],(String)row[2],license,
                              "SHOW_EXCERPTS") &&
                       canUse(c,actor.organizations(),(String)row[6],(String)row[2],license,
                              "EXPORT_DERIVATIVES") && row[3]!=null){
                        allowedSources.add(new Source((UUID)row[0],org,
                            (String)row[2],(Integer)row[3],(String)row[4]));
                    }
                }
                var all=query(c,STORED+"""
                    WHERE p.organization_id=ANY(?) AND p.parameter_kind='EF_FUEL'
                    ORDER BY p.created_at DESC,p.id DESC LIMIT 120
                    """,OpenFossilFactorCandidates::stored,array);
                var candidates=new java.util.ArrayList<Candidate>();
                for(Stored row:all)candidates.add(view(c,actor.organizations(),row));
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_OPEN_FOSSIL_FACTOR");
                return new Board(actor.id(),managers.canManage(actor.id()),
                    reviewers.contains(actor.id()),List.copyOf(allowedSources),
                    List.copyOf(candidates));
            }finally{array.free();}
        });
    }

    List<Row> rows(String cookie,UUID object){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,cookie);
            var origin=origin(c,object,null);
            validateScope(actor.organizations(),origin);
            requirePermissions(c,actor.organizations(),origin);
            var rows=query(c,"""
                SELECT row_index,record_key,period_year,geography,unit,value_numeric,locator
                FROM app_platform_source_candidate f
                JOIN app_platform_source_assessment a ON a.object_id=f.object_id
                  AND a.metric=f.metric AND a.period_year=f.period_year
                  AND a.geography=f.geography AND a.unit=f.unit
                WHERE f.object_id=? AND f.metric=?
                  AND a.methodology='VM0038'
                  AND a.methodology_edition='1.1' AND a.scenario=?
                  AND a.blocked_capability='BASELINE_BE'
                  AND a.outcome='APPROVED_FOR_SCOPE'
                ORDER BY f.row_index LIMIT 2000
                """,r->new Row(r.getInt(1),r.getString(2),r.getInt(3),
                    r.getString(4),r.getString(5),r.getBigDecimal(6),r.getString(7)),
                    object,METRIC,SCENARIO);
            ComplianceStaff.audit(c,actor.id(),null,"VIEW_OPEN_FOSSIL_FACTOR");
            return List.copyOf(rows);
        });
    }

    Candidate register(String cookie,Register request){
        return database.transaction(c->{
            var actor=managers.requireManager(c,cookie);
            if(request==null || request.sourceObjectId()==null ||
                request.rowIndex()==null || request.rowIndex()<2 ||
                request.rowIndex()>2001)throw invalid("rowIndex",
                "Selecione uma linha extraída de fonte aprovada, sem digitar um fator.");
            var origin=origin(c,request.sourceObjectId(),request.rowIndex());
            validateScope(actor.organizations(),origin);
            requirePermissions(c,actor.organizations(),origin);
            if(origin.value()==null || origin.value().signum()<=0)
                throw invalid("value","Fator original positivo obrigatório.");
            var preview=OpenEmissionParameterMath.convert(
                PARAMETER,GAS,origin.unit(),origin.value());
            if(!"tCO2e/USgal".equals(preview.normalizedUnit()))
                throw invalid("unit","A unidade resultante deve ser tCO2e/USgal.");
            UUID id=UUID.randomUUID();
            try{
                update(c,"""
                    INSERT INTO app_open_parameter_candidate
                    (id,organization_id,source_object_id,source_license_id,source_row,
                     source_sha256,source_locator,created_by,methodology,methodology_edition,
                     scenario,geography,period_year,parameter_kind,gas,fuel,grid,
                     original_unit,original_value,normalized_unit,normalized_value,
                     conversion_formula,technical_rule_ref,technical_evidence)
                    VALUES (?,?,?,?,?,?,?,?,'VM0038','1.1',?,'BR',?,'EF_FUEL',
                            'CO2e','GASOLINE',NULL,?,?,?,?,?,?,?)
                    """,id,origin.organization(),origin.object(),origin.license(),
                    origin.row(),HexFormat.of().parseHex(origin.sha()),origin.locator(),
                    actor.id(),SCENARIO,origin.year(),preview.originalUnit(),
                    preview.originalValue(),preview.normalizedUnit(),preview.normalizedValue(),
                    preview.formula(),"VM0038-1.1-EQ1-EF_FUEL",origin.locator());
            }catch(SQLException e){
                if("23505".equals(e.getSQLState()))
                    throw conflict("Esta linha e dimensões já foram registradas; utilize nova edição de fonte.");
                throw e;
            }
            ComplianceStaff.audit(c,actor.id(),null,"REGISTER_OPEN_FOSSIL_FACTOR");
            return view(c,actor.organizations(),load(c,id));
        });
    }

    Candidate review(String cookie,UUID id,Review request){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,cookie);
            if(!reviewers.contains(actor.id()))
                throw hidden("A revisão de fatores exige credencial técnica de revisão.");
            if(request==null || !Set.of("APPROVED_FOR_SCOPE","REJECTED_FOR_SCOPE",
                 "NEEDS_INFORMATION").contains(request.outcome()))
                throw invalid("outcome","Informe avaliação técnica interna válida.");
            String rule=bounded(request.ruleReference(),"ruleReference",8,300);
            String locator=bounded(request.evidenceLocator(),"evidenceLocator",3,300);
            String reason=bounded(request.reason(),"reason",15,2500);
            Stored row=load(c,id);
            if(row==null || !actor.organizations().contains(row.organization()))
                throw hidden("Candidato não está disponível.");
            if(actor.id().equals(row.author()) ||
               actor.id().equals(row.uploader()) ||
               actor.id().equals(row.submitter()))
                throw hidden("É obrigatória revisão independente do cadastro e da fonte.");
            if(!"CANDIDATE".equals(row.status()) || row.reviewOutcome()!=null)
                throw conflict("Fator já avaliado ou suspenso; crie nova edição.");
            requirePermissions(c,actor.organizations(),row.sourceId(),row.edition(),row.license());
            if(row.normalized()==null || row.original()==null)
                throw conflict("Parâmetro sem conteúdo autorizado.");
            UUID assessment=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_parameter_assessment
                (id,candidate_id,methodology,methodology_edition,scenario,
                 blocked_capability,outcome,rule_reference,evidence_locator,reason,reviewed_by)
                VALUES (?,?,'VM0038','1.1',?,'BASELINE_BE',?,?,?,?,?)
                """,assessment,id,SCENARIO,request.outcome(),rule,locator,reason,actor.id());
            ComplianceStaff.audit(c,actor.id(),null,"REVIEW_OPEN_FOSSIL_FACTOR");
            return view(c,actor.organizations(),load(c,id));
        });
    }

    private static Origin origin(Connection c,UUID object,Integer row)throws SQLException{
        return first(c,"""
            SELECT o.id,o.organization_id,o.license_id,o.uploaded_by,s.submitted_by,
              s.source_id,s.edition,o.state,s.state,encode(o.sha256,'hex'),
              f.row_index,f.record_key,f.period_year,f.geography,f.unit,
              f.value_numeric,f.locator,a.outcome,a.reviewed_by
            FROM app_platform_source_object o
            JOIN app_platform_source_submission s ON s.id=o.submission_id
            JOIN app_methodology_source_need n ON n.code=s.need_code
            JOIN app_platform_source_assessment a ON a.object_id=o.id
            JOIN app_platform_source_candidate f ON f.object_id=o.id
            WHERE o.id=? AND (?::int IS NULL OR f.row_index=?)
              AND s.need_code='OPEN-FOSSIL-EF' AND n.methodology='VM0038'
              AND n.methodology_edition='1.1' AND n.scenario=?
              AND n.blocked_capability='BASELINE_BE'
              AND a.methodology='VM0038' AND a.methodology_edition='1.1'
              AND a.scenario=? AND a.blocked_capability='BASELINE_BE'
              AND a.metric=? AND a.period_year=f.period_year
              AND a.geography=f.geography AND a.unit=f.unit
              AND f.metric=? AND f.geography='BR'
            ORDER BY f.row_index LIMIT 1
            """,r->new Origin(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getObject(3,UUID.class),r.getObject(4,UUID.class),
                r.getObject(5,UUID.class),r.getString(6),r.getString(7),
                r.getString(8),r.getString(9),r.getString(10),
                r.getInt(11),r.getString(12),r.getInt(13),r.getString(14),
                r.getString(15),r.getBigDecimal(16),r.getString(17),
                r.getString(18),r.getObject(19,UUID.class)),
                object,row,row,SCENARIO,SCENARIO,METRIC,METRIC);
    }

    private static void validateScope(Set<UUID> organizations,Origin o){
        if(o==null || !organizations.contains(o.organization()) ||
           !"APPROVED_FOR_SCOPE".equals(o.objectState()) ||
           !"METADATA_ACCEPTED".equals(o.metadataState()) ||
           !"APPROVED_FOR_SCOPE".equals(o.assessmentOutcome()) ||
           !"BR".equals(o.geography()) ||
           o.initialReviewer()==null ||
           o.initialReviewer().equals(o.uploadedBy()) ||
           o.initialReviewer().equals(o.submittedBy()))
            throw hidden("Origem não aprovada ou fora da organização e escopo.");
    }
    private void requirePermissions(Connection c,Set<UUID> orgs,Origin origin)
        throws SQLException{
        requirePermissions(c,orgs,origin.sourceId(),origin.edition(),origin.license());
    }
    private void requirePermissions(Connection c,Set<UUID> orgs,String source,String edition,
                                    UUID license)throws SQLException{
        rights.requireGrant(c,orgs,source,edition,license,"EXTRACT_TEXT");
        rights.requireGrant(c,orgs,source,edition,license,"SHOW_EXCERPTS");
        rights.requireGrant(c,orgs,source,edition,license,"EXPORT_DERIVATIVES");
    }
    private boolean canUse(Connection c,Set<UUID> orgs,String source,String edition,
        UUID license,String operation)throws SQLException{
        return rights.granted(c,orgs,source,edition,license,operation)!=null;
    }

    private static final String STORED="""
        SELECT p.id,p.organization_id,p.source_object_id,p.source_license_id,
          p.source_row,encode(p.source_sha256,'hex'),p.source_locator,
          p.period_year,p.fuel,p.gas,p.original_unit,p.original_value,
          p.normalized_unit,p.normalized_value,p.conversion_formula,
          p.status,p.created_by,o.uploaded_by,s.submitted_by,s.source_id,s.edition,
          a.outcome,a.rule_reference,a.evidence_locator,a.reason
        FROM app_open_parameter_candidate p
        JOIN app_platform_source_object o ON o.id=p.source_object_id
        JOIN app_platform_source_submission s ON s.id=o.submission_id
        LEFT JOIN app_open_parameter_assessment a ON a.candidate_id=p.id
          AND a.methodology='VM0038' AND a.methodology_edition='1.1'
          AND a.scenario='OPEN_LDV_DCFC_SIN_BR'
          AND a.blocked_capability='BASELINE_BE'
        """;
    private static Stored stored(java.sql.ResultSet r)throws SQLException{
        return new Stored(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),r.getInt(5),
            r.getString(6),r.getString(7),r.getInt(8),r.getString(9),r.getString(10),
            r.getString(11),r.getBigDecimal(12),r.getString(13),r.getBigDecimal(14),
            r.getString(15),r.getString(16),r.getObject(17,UUID.class),
            r.getObject(18,UUID.class),r.getObject(19,UUID.class),
            r.getString(20),r.getString(21),r.getString(22),r.getString(23),
            r.getString(24),r.getString(25));
    }
    private static Stored load(Connection c,UUID id)throws SQLException{
        return first(c,STORED+" WHERE p.id=? AND p.parameter_kind='EF_FUEL' ",
            OpenFossilFactorCandidates::stored,id);
    }
    private Candidate view(Connection c,Set<UUID> orgs,Stored row)throws SQLException{
        boolean active=row!=null && "CANDIDATE".equals(row.status()) &&
            canUse(c,orgs,row.sourceId(),row.edition(),row.license(),"EXTRACT_TEXT") &&
            canUse(c,orgs,row.sourceId(),row.edition(),row.license(),"SHOW_EXCERPTS") &&
            canUse(c,orgs,row.sourceId(),row.edition(),row.license(),"EXPORT_DERIVATIVES");
        String state=active?
             row.reviewOutcome()==null?"PENDING_INTERNAL_REVIEW_NOT_ADMITTED":
             "APPROVED_FOR_SCOPE".equals(row.reviewOutcome())?
                  "CHECKED_INTERNAL_NOT_ADMITTED":"REVIEW_BLOCKED_NOT_ADMITTED":
             "RIGHTS_UNAVAILABLE_NOT_ADMITTED";
        return new Candidate(row.id(),row.organization(),row.source(),row.license(),
            row.row(),row.sha(),active?row.locator():null,row.year(),row.fuel(),
            row.gas(),row.unit(),active?row.original():null,row.target(),
            active?row.normalized():null,row.formula(),state,row.reviewOutcome(),
            row.reviewRule(),active?row.reviewEvidence():null,
            active?row.reviewReason():null,active,false);
    }
    private static String bounded(String text,String field,int min,int max){
        if(text==null || text.strip().length()<min || text.strip().length()>max)
            throw invalid(field,"Forneça justificativa, regra e localizador verificáveis.");
        return text.strip();
    }
    private static PilotProblem invalid(String field,String msg){
        return PilotProblem.invalid("EF_FUEL_INVALID",field,RULE,msg);
    }
    private static PilotProblem hidden(String msg){
        return new PilotProblem(404,"EF_FUEL_NOT_AVAILABLE","source",RULE,msg);
    }
    private static PilotProblem conflict(String msg){
        return new PilotProblem(409,"EF_FUEL_CONFLICT","source",RULE,msg);
    }
}
