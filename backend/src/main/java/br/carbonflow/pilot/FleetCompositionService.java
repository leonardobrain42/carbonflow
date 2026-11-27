package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Platform-only national fleet EVR workbench. An imported snapshot is immutable
 * preparatory evidence, not an admitted ProjectSelection or an E8 parameter.
 */
@ApplicationScoped
class FleetCompositionService {
    private static final String RULE="CF-OPEN-005";

    /** {@code scope} is a label for the initial cut (LDV, BEV), never an admission; UNKNOWN stays pending. */
    record Model(UUID id,UUID organizationId,String manufacturer,String modelName,
                 String variant,String vehicleClass,String propulsion,OffsetDateTime recordedAt,
                 int classificationVersion,String scope) {}
    record ClassificationInput(String vehicleClass,String propulsion,String reason,String evidence) {}
    static final String SCOPE_CANDIDATE="CANDIDATE_INITIAL_SCOPE",SCOPE_OUTSIDE="OUTSIDE_INITIAL_SCOPE",
            SCOPE_PENDING="PENDING_CLASSIFICATION";
    static String scope(String vehicleClass,String propulsion){
        if("UNKNOWN".equals(vehicleClass) || "UNKNOWN".equals(propulsion)) return SCOPE_PENDING;
        return "LDV".equals(vehicleClass) && "BEV".equals(propulsion) ? SCOPE_CANDIDATE : SCOPE_OUTSIDE;
    }
    private static Model model(java.sql.ResultSet r)throws SQLException{
        String cls=r.getString(6),prop=r.getString(7);
        return new Model(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getString(3),r.getString(4),
            r.getString(5),cls,prop,r.getObject(8,OffsetDateTime.class),r.getInt(9),scope(cls,prop));
    }
    record Registration(UUID organizationId,String manufacturer,String modelName,
                        String variant,String vehicleClass,String propulsion,
                        Integer aliasYear,String sourceRecordKey) {}
    record AliasInput(UUID organizationId,Integer year,String sourceRecordKey) {}
    record Alias(UUID organizationId,int observationYear,String recordKey,UUID modelId) {}
    record Source(UUID id,UUID organizationId,String needCode,String state,UUID licenseId,
                  String sourceId,String edition,String sha256,String metadataState,
                  OffsetDateTime purgedAt,Integer referenceYear,OffsetDateTime uploadedAt) {}
    record SourceView(UUID id,UUID organizationId,String needCode,String state,
                      String sourceId,String edition,String sha256,
                      boolean rightsActive,boolean approvedForScope,
                      OffsetDateTime uploadedAt) {}
    record Line(int sourceRow,String recordKey,BigDecimal quantity,int year,
                String metric,String unit,String geography,UUID modelId,
                String modelName,String vehicleClass,String propulsion,String locator) {}
    record Preview(UUID sourceObjectId,String needCode,String dataKind,int observationYear,
                   String geography,boolean sourceAssessed,boolean rightsActive,
                   long denominator,boolean valid,boolean canCreateSnapshot,
                   List<Line> rows,List<String> issues,boolean usableForE8) {}
    record Snapshot(UUID id,UUID ingestionId,UUID organizationId,String dataKind,int year,
                    String geography,String sha256,Long denominator,Integer modelCount,
                    String state,String coverageState,String ratingState,
                    OffsetDateTime createdAt,boolean usableForE8) {}
    record Weight(UUID modelId,String modelName,long evr,BigDecimal weight,String locator) {}
    record CoverageDecision(UUID id,UUID snapshotId,int version,String outcome,
                            Long citedTotal,Long gapVehicles,String reason,
                            String viewState,boolean usableForE8) {}
    record Detail(Snapshot snapshot,List<Weight> weights,CoverageDecision coverageDecision) {}
    record DecisionResult(Detail detail,boolean created) {}
    record Board(boolean canManage,boolean canReviewCoverage,List<UUID> organizations,List<Model> models,
                 List<Alias> aliases,List<SourceView> sources,List<Snapshot> snapshots,
                 String missingNeed,String guidance) {}
    record Create(UUID sourceObjectId,Integer year) {}
    record CoverageInput(String outcome,String reason,Long citedTotal) {}
    private record CommandReceipt(String action,byte[] fingerprint,UUID recordId) {}

    private final PilotDatabase database;
    private final PlatformSourceReadiness readiness;
    private final PlatformSourceWork staffManagers;
    private final PlatformSourceFiles rights;
    private final Set<UUID> coverageReviewers;

    FleetCompositionService(PilotDatabase database,PlatformSourceReadiness readiness,
            PlatformSourceWork staffManagers,PlatformSourceFiles rights,
            @ConfigProperty(name="carbonflow.platform.fleet-coverage.reviewers") Optional<String> reviewers){
        this.database=database;
        this.readiness=readiness;
        this.staffManagers=staffManagers;
        this.rights=rights;
        try {
            this.coverageReviewers=reviewers.map(value->Arrays.stream(value.split(","))
                .map(String::strip).filter(item->!item.isEmpty()).map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch (IllegalArgumentException error) {
            throw new IllegalStateException("Lista de revisores da cobertura EVR inválida.", error);
        }
    }

    Board board(String token){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            var orgArray=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try {
                List<Model> models=query(c, """
                    SELECT id,organization_id,manufacturer,model_name,variant,vehicle_class,
                           propulsion,recorded_at,classification_version
                    FROM app_fleet_model_effective WHERE organization_id=ANY(?)
                    ORDER BY manufacturer,model_name,variant LIMIT 200
                    """,FleetCompositionService::model,orgArray);
                List<Alias> aliases=query(c, """
                    SELECT organization_id,observation_year,record_key,model_id
                    FROM app_fleet_model_alias WHERE organization_id=ANY(?)
                    ORDER BY observation_year DESC,record_key LIMIT 300
                    """,r->new Alias(r.getObject(1,UUID.class),r.getInt(2),
                    r.getString(3),r.getObject(4,UUID.class)),orgArray);
                List<Source> sources=query(c, """
                    SELECT o.id,o.organization_id,s.need_code,o.state,o.license_id,
                           s.source_id,s.edition,encode(o.sha256,'hex'),s.state,o.purged_at,
                           s.reference_year,o.uploaded_at
                    FROM app_platform_source_object o
                    JOIN app_platform_source_submission s ON s.id=o.submission_id
                    WHERE o.organization_id=ANY(?)
                      AND s.need_code IN ('OPEN-EVR','OPEN-EV-SALES')
                    ORDER BY o.uploaded_at DESC,o.id DESC LIMIT 60
                    """,FleetCompositionService::sourceRow,orgArray);
                List<SourceView> visible=new ArrayList<>();
                for(var source:sources) visible.add(sourceView(c,source));
                List<Snapshot> snapshots=query(c, """
                    SELECT i.id,i.organization_id,i.data_kind,i.observation_year,i.geography,
                           encode(i.source_sha256,'hex'),i.status,i.imported_at,i.purged_at,
                           i.source_object_id,i.source_license_id,s.id,s.denominator,
                           s.number_of_models,s.coverage_state,s.rating_state,
                           o.purged_at,s.purged_at
                    FROM app_fleet_ingestion i
                    LEFT JOIN app_fleet_composition_snapshot s ON s.ingestion_id=i.id
                    JOIN app_platform_source_object o ON o.id=i.source_object_id
                    WHERE i.organization_id=ANY(?)
                    ORDER BY i.imported_at DESC,i.id DESC LIMIT 80
                    """,r->{
                    UUID importId=r.getObject(1,UUID.class);
                    UUID org=r.getObject(2,UUID.class);
                    String kind=r.getString(3);
                    int year=r.getInt(4);
                    UUID object=r.getObject(10,UUID.class);
                    UUID licence=r.getObject(11,UUID.class);
                    String state=r.getString(7);
                    OffsetDateTime revoked=r.getObject(9,OffsetDateTime.class);
                    OffsetDateTime originPurged=r.getObject(17,OffsetDateTime.class);
                    UUID snapshotId=r.getObject(12,UUID.class);
                    // Do not send retained numerical content if source rights are unavailable.
                    var source=first(c, """
                        SELECT s.source_id,s.edition FROM app_platform_source_submission s
                        JOIN app_platform_source_object o ON o.submission_id=s.id
                        WHERE o.id=?
                        """,rs->new String[]{rs.getString(1),rs.getString(2)},object);
                    boolean licensed=revoked==null && originPurged==null && source!=null &&
                        rights.granted(c,Set.of(org),source[0],source[1],licence,"SHOW_EXCERPTS")!=null &&
                        rights.granted(c,Set.of(org),source[0],source[1],licence,"EXPORT_DERIVATIVES")!=null;
                    boolean stale=licensed && first(c, """
                        SELECT true FROM app_fleet_ingestion later
                        WHERE later.organization_id=? AND later.observation_year=?
                          AND later.geography=? AND later.data_kind=?
                          AND later.imported_at>?
                          AND later.status='PREPARATORY' LIMIT 1
                        """,rs->true,org,year,r.getString(5),kind,
                        r.getObject(8,OffsetDateTime.class))!=null;
                    return new Snapshot(snapshotId,importId,org,kind,year,r.getString(5),
                        r.getString(6),licensed?(Long)r.getObject(13):null,
                        licensed && snapshotId!=null ? r.getInt(14):null,
                        !licensed?"RIGHTS_UNAVAILABLE":stale?"STALE_REFERENCE":state,
                        licensed?r.getString(15):"NOT_AVAILABLE",
                        licensed?r.getString(16):"NOT_AVAILABLE",
                        r.getObject(8,OffsetDateTime.class),false);
                    },orgArray);
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_PLATFORM_FLEET");
                return new Board(staffManagers.canManage(actor.id()),
                    coverageReviewers.contains(actor.id()),
                    actor.organizations().stream().sorted().toList(),
                    List.copyOf(models),List.copyOf(aliases),List.copyOf(visible),
                    List.copyOf(snapshots),"OPEN-EVR",
                    "Falta de estoque BEV nacional por modelo não deve ser preenchida com PBEV, vendas ou amostra SIM.");
            }finally{orgArray.free();}
        });
    }

    Model register(String token,String key,Registration requested){
        return database.transaction(c->{
            var actor=staffManagers.requireManager(c,token);
            if(requested==null || requested.organizationId()==null ||
                    !actor.organizations().contains(requested.organizationId()))
                throw invisible();
            String maker=label(requested.manufacturer(),120,"manufacturer"),
                    name=label(requested.modelName(),120,"modelName"),
                    variant=label(requested.variant(),120,"variant");
            if (!("LDV".equals(requested.vehicleClass()) || "HDV".equals(requested.vehicleClass())
                    || "OTHER".equals(requested.vehicleClass()) || "UNKNOWN".equals(requested.vehicleClass()))
                || !("BEV".equals(requested.propulsion()) || "PHEV".equals(requested.propulsion())
                    || "OTHER".equals(requested.propulsion()) || "UNKNOWN".equals(requested.propulsion())))
                throw invalid("classification","Informe categoria e propulsão sem inferir BEV.");
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("REGISTER_MODEL|"+requested);
            var replay=receipt(c,actor.id(),command,"REGISTER_MODEL",fingerprint);
            if(replay!=null)return findModel(c,replay.recordId(),requested.organizationId());
            UUID id=UUID.randomUUID();
            UUID created=first(c, """
                INSERT INTO app_fleet_model
                (id,organization_id,manufacturer,model_name,variant,vehicle_class,propulsion,created_by)
                VALUES (?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """,r->r.getObject(1,UUID.class),id,requested.organizationId(),
                maker,name,variant,requested.vehicleClass(),requested.propulsion(),actor.id());
            if(created==null)throw new PilotProblem(409,"MODELO_JA_CADASTRADO","modelName",RULE,
                "Este modelo e variante já foram cadastrados nesta organização.");
            if(requested.aliasYear()!=null || requested.sourceRecordKey()!=null) {
                addAlias(c,actor.id(),requested.organizationId(),created,requested.aliasYear(),
                    requested.sourceRecordKey());
            }
            remember(c,actor.id(),command,"REGISTER_MODEL",fingerprint,created);
            ComplianceStaff.audit(c,actor.id(),null,"REGISTER_PLATFORM_FLEET_MODEL");
            return findModel(c,created,requested.organizationId());
        });
    }

    /**
     * Append-only classification of a model (D19 slice 2). Known values need evidence; (UNKNOWN, UNKNOWN) records that
     * the model is explicitly pending. A decision equal to the current one is refused. It changes no past snapshot.
     */
    Model classify(String token,String key,UUID modelId,ClassificationInput input){
        return database.transaction(c->{
            var actor=staffManagers.requireManager(c,token);
            UUID org=first(c,"SELECT organization_id FROM app_fleet_model WHERE id=?",r->r.getObject(1,UUID.class),modelId);
            if(org==null || !actor.organizations().contains(org))throw invisible();
            if(input==null || !Set.of("LDV","HDV","OTHER","UNKNOWN").contains(input.vehicleClass())
                    || !Set.of("BEV","PHEV","OTHER","UNKNOWN").contains(input.propulsion()))
                throw invalid("classification","Informe classe e propulsão; desconhecido fica pendente.");
            boolean unknown="UNKNOWN".equals(input.vehicleClass()) && "UNKNOWN".equals(input.propulsion());
            if(("UNKNOWN".equals(input.vehicleClass())) != ("UNKNOWN".equals(input.propulsion())))
                throw invalid("classification","Classe e propulsão desconhecidas só valem juntas; senão informe as duas.");
            String reason=input.reason()==null?"":input.reason().strip();
            String evidence=input.evidence()==null||input.evidence().isBlank()?null:input.evidence().strip();
            if(reason.length()<10 || reason.length()>1000)throw invalid("reason","O motivo precisa ter entre 10 e 1000 caracteres.");
            if(!unknown && (evidence==null || evidence.length()<3 || evidence.length()>1000))
                throw invalid("evidence","Classificação conhecida exige evidência (fonte e localizador).");
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("CLASSIFY_MODEL|"+modelId+"|"+input);
            var replay=receipt(c,actor.id(),command,"CLASSIFY_MODEL",fingerprint);
            if(replay!=null)return findModel(c,modelId,org);
            var current=findModel(c,modelId,org);
            if(current.vehicleClass().equals(input.vehicleClass()) && current.propulsion().equals(input.propulsion()))
                throw new PilotProblem(409,"CLASSIFICACAO_IGUAL","classification",RULE,"A classificação informada já é a vigente.");
            int version=current.classificationVersion()+1;
            update(c,"""
                INSERT INTO app_fleet_model_classification
                (model_id,organization_id,version,vehicle_class,propulsion,reason,evidence,decided_by)
                VALUES (?,?,?,?,?,?,?,?)
                """,modelId,org,version,input.vehicleClass(),input.propulsion(),reason,evidence,actor.id());
            remember(c,actor.id(),command,"CLASSIFY_MODEL",fingerprint,modelId);
            ComplianceStaff.audit(c,actor.id(),null,"CLASSIFY_PLATFORM_FLEET_MODEL");
            return findModel(c,modelId,org);
        });
    }

    Alias alias(String token,String key,UUID modelId,AliasInput input){
        return database.transaction(c->{
            var actor=staffManagers.requireManager(c,token);
            if(input==null || input.organizationId()==null ||
                !actor.organizations().contains(input.organizationId()))throw invisible();
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("MAP_ALIAS|"+modelId+"|"+input);
            var replay=receipt(c,actor.id(),command,"MAP_ALIAS",fingerprint);
            if(replay!=null) return new Alias(input.organizationId(),input.year(),
                normalizeKey(input.sourceRecordKey()),modelId);
            addAlias(c,actor.id(),input.organizationId(),modelId,input.year(),input.sourceRecordKey());
            remember(c,actor.id(),command,"MAP_ALIAS",fingerprint,modelId);
            ComplianceStaff.audit(c,actor.id(),null,"MAP_PLATFORM_FLEET_ALIAS");
            return new Alias(input.organizationId(),input.year(),
                normalizeKey(input.sourceRecordKey()),modelId);
        });
    }

    Preview preview(String token,UUID object,int year){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            Preview result=analyze(c,actor.organizations(),object,year,false);
            ComplianceStaff.audit(c,actor.id(),null,"PREVIEW_PLATFORM_FLEET");
            return result;
        });
    }

    Detail create(String token,String key,Create input){
        return database.transaction(c->{
            var actor=staffManagers.requireManager(c,token);
            if(input==null || input.sourceObjectId()==null || input.year()==null)
                throw invalid("sourceObjectId","Escolha uma fonte e ano.");
            // Prevent competing imports from creating duplicate 'latest' decisions.
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                r->true,"fleet:"+input.sourceObjectId()+":"+input.year());
            Source source=source(c,actor.organizations(),input.sourceObjectId());
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("CREATE_SNAPSHOT|"+input);
            var replay=receipt(c,actor.id(),command,"CREATE_SNAPSHOT",fingerprint);
            Preview preview=analyze(c,actor.organizations(),input.sourceObjectId(),
                    input.year(),true);
            if(!preview.canCreateSnapshot())throw new PilotProblem(409,
                "SERIE_NAO_VALIDADA","sourceObjectId",RULE,
                "A origem, classificação, licenças ou dimensões precisam de revisão.");
            rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),source.edition(),
                source.licenseId(),"EXPORT_DERIVATIVES");
            String kind=preview.dataKind();
            if(replay!=null)return detail(c,replay.recordId(),source,actor.organizations());
            UUID existing=first(c,"""
                SELECT id FROM app_fleet_ingestion
                WHERE organization_id=? AND source_object_id=? AND data_kind=?
                  AND observation_year=? AND geography='BR'
                """,r->r.getObject(1,UUID.class),source.organizationId(),source.id(),kind,input.year());
            if(existing!=null){
                remember(c,actor.id(),command,"CREATE_SNAPSHOT",fingerprint,existing);
                return detail(c,existing,source,actor.organizations());
            }
            UUID ingestion=UUID.randomUUID();
            byte[] digest=HexFormat.of().parseHex(source.sha256());
            update(c,"""
                INSERT INTO app_fleet_ingestion
                 (id,organization_id,source_object_id,source_license_id,source_sha256,
                  data_kind,observation_year,geography,imported_by)
                VALUES (?,?,?,?,?,?,?,'BR',?)
                """,ingestion,source.organizationId(),source.id(),source.licenseId(),digest,
                kind,input.year(),actor.id());
            var rows=loadRows(c,source,input.year());
            var evaluated=FleetCompositionRules.evaluate(kind,input.year(),"BR",rows);
            if(!evaluated.valid())throw invalid("rows","Não é possível importar série com falhas dimensionais.");
            if(FleetCompositionRules.STOCK.equals(kind)){
                for(var row:evaluated.weights()){
                    update(c,"""
                        INSERT INTO app_fleet_stock_observation
                        (ingestion_id,organization_id,source_row,model_id,observed_stock,
                         record_key,locator)
                        VALUES (?,?,?,?,?,?,?)
                        """,ingestion,source.organizationId(),row.sourceRow(),row.modelId(),
                        row.quantity(),row.recordKey(),row.locator());
                }
                UUID snapshot=UUID.randomUUID();
                update(c,"""
                    INSERT INTO app_fleet_composition_snapshot
                    (id,ingestion_id,organization_id,observation_year,geography,
                     denominator,number_of_models,source_sha256)
                    VALUES (?,?,?,?,'BR',?,?,?)
                    """,snapshot,ingestion,source.organizationId(),input.year(),
                    evaluated.total(),evaluated.weights().size(),digest);
                for(var weight:evaluated.weights()){
                    update(c,"""
                        INSERT INTO app_fleet_composition_weight
                         (snapshot_id,organization_id,model_id,source_row,observed_stock,weight,source_locator)
                        VALUES (?,?,?,?,?,?,?)
                        """,snapshot,source.organizationId(),weight.modelId(),
                        weight.sourceRow(),weight.quantity(),weight.weight(),weight.locator());
                }
            }else{
                for(var row:rows) {
                    update(c,"""
                        INSERT INTO app_fleet_sales_observation
                         (ingestion_id,organization_id,source_row,model_id,new_sales,record_key,locator)
                        VALUES (?,?,?,?,?,?,?)
                        """,ingestion,source.organizationId(),row.sourceRow(),row.modelId(),
                        row.value().longValueExact(),row.recordKey(),row.locator());
                }
            }
            remember(c,actor.id(),command,"CREATE_SNAPSHOT",fingerprint,ingestion);
            ComplianceStaff.audit(c,actor.id(),null,"CREATE_PLATFORM_FLEET_SNAPSHOT");
            return detail(c,ingestion,source,actor.organizations());
        });
    }

    Detail get(String token,UUID ingestion){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            var array=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try{
                UUID sourceId=first(c, """
                    SELECT source_object_id FROM app_fleet_ingestion
                    WHERE id=? AND organization_id=ANY(?)
                    """,r->r.getObject(1,UUID.class),ingestion,array);
                if(sourceId==null)throw invisible();
                Source source=source(c,actor.organizations(),sourceId);
                if(source.purgedAt()!=null)throw invisible();
                rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),
                    source.edition(),source.licenseId(),"SHOW_EXCERPTS");
                rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),
                    source.edition(),source.licenseId(),"EXPORT_DERIVATIVES");
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_PLATFORM_FLEET");
                return detail(c,ingestion,source,actor.organizations());
            }finally{array.free();}
        });
    }

    DecisionResult decide(String token,String key,String ifMatch,UUID ingestion,CoverageInput input){
        return database.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            if(!coverageReviewers.contains(actor.id()))
                throw new PilotProblem(403,"COBERTURA_NAO_INDEPENDENTE","actor",RULE,
                    "Somente um revisor que não importou o retrato registra a cobertura.");
            int expected=PilotCommands.expected(ifMatch);
            String command=PilotCommands.key(key);
            if(input==null)throw invalid("outcome","Informe o resultado da cobertura.");
            String outcome=input.outcome();
            if(!"NOT_COVERED".equals(outcome) && !"NO_OFFICIAL_TOTAL".equals(outcome))
                throw invalid("outcome","A cobertura só pode ser recusada ou ficar sem total oficial.");
            String reason=reason(input.reason());
            Long cited="NO_OFFICIAL_TOTAL".equals(outcome)?null:input.citedTotal();
            if("NO_OFFICIAL_TOTAL".equals(outcome) && input.citedTotal()!=null)
                throw invalid("citedTotal","Sem total oficial não há número a citar.");
            if(cited!=null && (cited<1 || cited>1_000_000_000_000L))
                throw invalid("citedTotal","O total citado precisa ser um inteiro positivo.");
            byte[] fingerprint=AccessSecrets.digest("DECIDE_NATIONAL_COVERAGE|"+ingestion+"|"
                +outcome+"|"+reason+"|"+cited);
            var replay=receipt(c,actor.id(),command,"DECIDE_NATIONAL_COVERAGE",fingerprint);
            var array=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try{
                var row=first(c,"""
                    SELECT i.organization_id,i.imported_by,i.data_kind,i.observation_year,i.geography,
                           i.imported_at,i.purged_at,i.source_object_id,s.id,s.denominator,s.purged_at
                    FROM app_fleet_ingestion i
                    LEFT JOIN app_fleet_composition_snapshot s ON s.ingestion_id=i.id
                    WHERE i.id=? AND i.organization_id=ANY(?)
                    """,r->new Object[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                    r.getString(3),r.getInt(4),r.getString(5),r.getObject(6,OffsetDateTime.class),
                    r.getObject(7,OffsetDateTime.class),r.getObject(8,UUID.class),
                    r.getObject(9,UUID.class),(Long)r.getObject(10),
                    r.getObject(11,OffsetDateTime.class)},ingestion,array);
                if(row==null || row[6]!=null)throw invisible();
                Source source=source(c,actor.organizations(),(UUID)row[7]);
                if(source.purgedAt()!=null)throw invisible();
                rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),
                    source.edition(),source.licenseId(),"SHOW_EXCERPTS");
                if(replay!=null){
                    CoverageDecision saved=coverageById(c,replay.recordId());
                    Detail current=detail(c,ingestion,source,actor.organizations());
                    if(saved==null || current.snapshot().id()==null
                        || !current.snapshot().id().equals(saved.snapshotId()))
                        throw new PilotProblem(409,"CHAVE_REUTILIZADA","Idempotency-Key",RULE,
                            "Esta chave já identifica outro comando de frota.");
                    boolean older=current.coverageDecision()!=null
                        && current.coverageDecision().version()>saved.version();
                    return new DecisionResult(new Detail(current.snapshot(),current.weights(),
                        new CoverageDecision(saved.id(),saved.snapshotId(),saved.version(),
                            saved.outcome(),saved.citedTotal(),saved.gapVehicles(),saved.reason(),
                            older || "STALE_REFERENCE".equals(current.snapshot().state())?"STALE":"CURRENT",
                            false)),false);
                }
                if(!"STOCK_IN_CIRCULATION".equals(row[2]) || !"BR".equals(row[4]) || row[8]==null)
                    throw invalid("ingestion","A cobertura nacional vale só para estoque BEV em circulação.");
                if(actor.id().equals(row[1]))
                    throw new PilotProblem(403,"COBERTURA_NAO_INDEPENDENTE","actor",RULE,
                        "Quem importou o retrato não registra a cobertura.");
                if(row[9]==null || row[10]!=null)throw invisible();
                boolean stale=first(c,"""
                    SELECT true FROM app_fleet_ingestion later
                    WHERE later.organization_id=? AND later.observation_year=?
                      AND later.geography=? AND later.data_kind=?
                      AND later.imported_at>? AND later.status='PREPARATORY'
                    LIMIT 1
                    """,r->true,row[0],row[3],row[4],row[2],row[5])!=null;
                if(stale)throw new PilotProblem(409,"COBERTURA_CONFLITO","snapshot",RULE,
                    "Há um retrato posterior deste ano. A cobertura antiga não é reescrita.");
                long denominator=(Long)row[9];
                Long gap=null;
                if(cited!=null){
                    if(cited<=denominator)throw invalid("citedTotal",
                        "O total citado precisa ser maior que o estoque deste retrato.");
                    gap=cited-denominator;
                }
                CoverageDecision current=coverage(c,(UUID)row[8],false);
                int version=current==null?0:current.version();
                if(current!=null && expected+1==version && actor.id().equals(decider(c,current.id()))
                    && outcome.equals(current.outcome()) && reason.equals(current.reason())
                    && java.util.Objects.equals(cited,current.citedTotal()))
                    return new DecisionResult(detail(c,ingestion,source,actor.organizations()),false);
                PilotCommands.requireVersion(expected,version);
                UUID id=UUID.randomUUID();
                update(c,"""
                    INSERT INTO app_fleet_national_coverage_decision
                    (id,snapshot_id,organization_id,version,outcome,cited_total,gap_vehicles,reason,decided_by)
                    VALUES (?,?,?,?,?,?,?,?,?)
                    """,id,row[8],row[0],version+1,outcome,cited,gap,reason,actor.id());
                remember(c,actor.id(),command,"DECIDE_NATIONAL_COVERAGE",fingerprint,id);
                ComplianceStaff.audit(c,actor.id(),null,"DECIDE_PLATFORM_FLEET_COVERAGE");
                return new DecisionResult(detail(c,ingestion,source,actor.organizations()),true);
            }finally{array.free();}
        });
    }

    private Detail detail(Connection c,UUID ingestion,Source source,Set<UUID> orgs)
        throws SQLException{
        var row=first(c,"""
            SELECT i.id,i.data_kind,i.observation_year,i.geography,
                   encode(i.source_sha256,'hex'),i.imported_at,i.purged_at,i.organization_id,
                   s.id,s.denominator,s.number_of_models,s.coverage_state,s.rating_state
            FROM app_fleet_ingestion i
            LEFT JOIN app_fleet_composition_snapshot s ON s.ingestion_id=i.id
            WHERE i.id=?
            """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2),
            r.getInt(3),r.getString(4),r.getString(5),r.getObject(6,OffsetDateTime.class),
            r.getObject(7,OffsetDateTime.class),r.getObject(8,UUID.class),
            r.getObject(9,UUID.class),(Long)r.getObject(10),(Integer)r.getObject(11),
            r.getString(12),r.getString(13)},ingestion);
        if(row==null || !orgs.contains((UUID)row[7]))throw invisible();
        if(row[6]!=null || source.purgedAt()!=null)throw invisible();
        boolean stale=first(c,"""
            SELECT true FROM app_fleet_ingestion later
            WHERE later.organization_id=? AND later.observation_year=?
              AND later.geography=? AND later.data_kind=?
              AND later.imported_at>? AND later.status='PREPARATORY'
            LIMIT 1
            """,r->true,row[7],row[2],row[3],row[1],row[5])!=null;
        Snapshot snapshot=new Snapshot((UUID)row[8],(UUID)row[0],(UUID)row[7],
            (String)row[1],(Integer)row[2],(String)row[3],(String)row[4],
            (Long)row[9],(Integer)row[10],stale?"STALE_REFERENCE":"PREPARATORY",
            row[11]==null?"NOT_APPLICABLE":(String)row[11],
            row[12]==null?"NOT_APPLICABLE":(String)row[12],
            (OffsetDateTime)row[5],false);
        List<Weight> weights;
        if(row[8]==null)weights=List.of();
        else weights=query(c,"""
            SELECT w.model_id,concat(m.manufacturer,' ',m.model_name,' ',m.variant),
                   w.observed_stock,w.weight,w.source_locator
            FROM app_fleet_composition_weight w
            JOIN app_fleet_model m ON m.id=w.model_id
            WHERE w.snapshot_id=? ORDER BY w.observed_stock DESC,w.model_id
            LIMIT 2000
            """,r->new Weight(r.getObject(1,UUID.class),r.getString(2),
            r.getLong(3),r.getBigDecimal(4),r.getString(5)),row[8]);
        return new Detail(snapshot,List.copyOf(weights),
            row[8]==null?null:coverage(c,(UUID)row[8],stale));
    }

    private static CoverageDecision coverageById(Connection c,UUID id)throws SQLException{
        return first(c,"""
            SELECT id,snapshot_id,version,outcome,cited_total,gap_vehicles,reason
            FROM app_fleet_national_coverage_decision WHERE id=?
            """,r->new CoverageDecision(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getInt(3),r.getString(4),(Long)r.getObject(5),(Long)r.getObject(6),
            r.getString(7),"CURRENT",false),id);
    }

    private static CoverageDecision coverage(Connection c,UUID snapshot,boolean stale)throws SQLException{
        return first(c,"""
            SELECT id,snapshot_id,version,outcome,cited_total,gap_vehicles,reason
            FROM app_fleet_national_coverage_decision
            WHERE snapshot_id=? ORDER BY version DESC LIMIT 1
            """,r->new CoverageDecision(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getInt(3),r.getString(4),(Long)r.getObject(5),(Long)r.getObject(6),
            r.getString(7),stale?"STALE":"CURRENT",false),snapshot);
    }

    private static UUID decider(Connection c,UUID decision)throws SQLException{
        return first(c,"SELECT decided_by FROM app_fleet_national_coverage_decision WHERE id=?",
            r->r.getObject(1,UUID.class),decision);
    }

    private static String reason(String value){
        if(value==null)throw invalid("reason","Descreva a comparação com o estoque nacional.");
        String normalized=value.strip();
        if(normalized.length()<20 || normalized.length()>500
            || normalized.codePoints().anyMatch(Character::isISOControl))
            throw invalid("reason","A justificativa precisa ter entre 20 e 500 caracteres.");
        return normalized;
    }

    private Preview analyze(Connection c,Set<UUID> orgs,UUID object,int year,boolean forImport)
        throws SQLException{
        if(year<1900 || year>2100)throw invalid("year","Ano de observação inválido.");
        Source source=source(c,orgs,object);
        if(source.purgedAt()!=null || !"METADATA_ACCEPTED".equals(source.metadataState()) ||
            !Set.of("NORMALIZED_CANDIDATE","APPROVED_FOR_SCOPE").contains(source.state()))
            throw invisible();
        rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),source.edition(),
            source.licenseId(),"EXTRACT_TEXT");
        rights.requireGrant(c,Set.of(source.organizationId()),source.sourceId(),source.edition(),
            source.licenseId(),"SHOW_EXCERPTS");
        String kind=kind(source.needCode());
        boolean assessed=first(c,"""
            SELECT true FROM app_platform_source_assessment
            WHERE object_id=? AND outcome='APPROVED_FOR_SCOPE'
              AND methodology='VM0038' AND methodology_edition='1.1'
              AND scenario='OPEN_LDV_DCFC_SIN_BR'
              AND metric=? AND period_year=? AND geography='BR' AND unit='vehicles'
              AND blocked_capability=?
            LIMIT 1
            """,r->true,object,metric(kind),year,
            FleetCompositionRules.STOCK.equals(kind)?"BASELINE_BE":"APPLICABILITY")!=null;
        List<FleetCompositionRules.Row> sourceRows=loadRows(c,source,year);
        var calculated=FleetCompositionRules.evaluate(kind,year,"BR",sourceRows);
        var issues=new ArrayList<>(calculated.issues());
        if(!assessed)issues.add("Parecer independente APPROVED_FOR_SCOPE para métrica/ano/BR/vehicles ainda ausente.");
        if(!"APPROVED_FOR_SCOPE".equals(source.state()))
            issues.add("O arquivo ainda não recebeu parecer técnico de escopo.");
        if(FleetCompositionRules.SALES.equals(kind))
            issues.add("Esta é uma série de vendas, não representa estoque EVR nem produz pesos.");
        List<Line> values=sourceRows.stream().map(row->{
            String name=row.modelId()==null?null:sourceRowsName(c,row.modelId());
            return new Line(row.sourceRow(),row.recordKey(),row.value(),row.observationYear(),
                row.metric(),row.unit(),row.geography(),row.modelId(),name,
                row.vehicleClass(),row.propulsion(),row.locator());
        }).toList();
        boolean canCreate=calculated.valid() && assessed &&
            "APPROVED_FOR_SCOPE".equals(source.state());
        return new Preview(object,source.needCode(),kind,year,"BR",assessed,true,
            calculated.valid()?calculated.total():0,calculated.valid(),canCreate,
            List.copyOf(values),List.copyOf(issues),false);
    }

    private String sourceRowsName(Connection c,UUID model) {
        try {
            return first(c,"SELECT concat(manufacturer,' ',model_name,' ',variant) FROM app_fleet_model WHERE id=?",
                r->r.getString(1),model);
        }catch(SQLException error){throw new IllegalStateException(error);}
    }

    private List<FleetCompositionRules.Row> loadRows(Connection c,Source source,int year)
        throws SQLException{
        return query(c,"""
            SELECT c.row_index,c.record_key,c.value_numeric,c.metric,c.unit,c.period_year,
                   c.geography,a.model_id,m.vehicle_class,m.propulsion,c.locator
            FROM app_platform_source_candidate c
            LEFT JOIN app_fleet_model_alias a ON a.organization_id=?
              AND a.observation_year=? AND a.record_key=upper(c.record_key)
            LEFT JOIN app_fleet_model_effective m ON m.id=a.model_id AND m.organization_id=?
            WHERE c.object_id=? ORDER BY c.row_index LIMIT 2001
            """,r->new FleetCompositionRules.Row(r.getInt(1),r.getObject(8,UUID.class),
                r.getString(2),r.getBigDecimal(3),r.getString(4),r.getString(5),
                r.getInt(6),r.getString(7),r.getString(9),r.getString(10),r.getString(11)),
                source.organizationId(),year,source.organizationId(),source.id());
    }

    private Source source(Connection c,Set<UUID> orgs,UUID id)throws SQLException {
        var allowed=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            Source s=first(c, """
                SELECT o.id,o.organization_id,s.need_code,o.state,o.license_id,s.source_id,
                       s.edition,encode(o.sha256,'hex'),s.state,o.purged_at,
                       s.reference_year,o.uploaded_at
                FROM app_platform_source_object o
                JOIN app_platform_source_submission s ON s.id=o.submission_id
                JOIN app_methodology_source_need n ON n.code=s.need_code
                WHERE o.id=? AND o.organization_id=ANY(?)
                  AND n.methodology='VM0038' AND n.methodology_edition='1.1'
                  AND n.scenario='OPEN_LDV_DCFC_SIN_BR'
                  AND s.need_code IN ('OPEN-EVR','OPEN-EV-SALES')
                """,FleetCompositionService::sourceRow,id,allowed);
            if(s==null)throw invisible();
            return s;
        }finally{allowed.free();}
    }

    private static Source sourceRow(java.sql.ResultSet r)throws SQLException{
        return new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getString(3),r.getString(4),r.getObject(5,UUID.class),
            r.getString(6),r.getString(7),r.getString(8),r.getString(9),
            r.getObject(10,OffsetDateTime.class),(Integer)r.getObject(11),
            r.getObject(12,OffsetDateTime.class));
    }

    private SourceView sourceView(Connection c,Source row)throws SQLException {
        boolean active=row.purgedAt()==null &&
            rights.granted(c,Set.of(row.organizationId()),row.sourceId(),row.edition(),
                row.licenseId(),"EXTRACT_TEXT")!=null &&
            rights.granted(c,Set.of(row.organizationId()),row.sourceId(),row.edition(),
                row.licenseId(),"SHOW_EXCERPTS")!=null;
        return new SourceView(row.id(),row.organizationId(),row.needCode(),
            active?row.state():"RIGHTS_UNAVAILABLE",
            row.sourceId(),row.edition(),row.sha256(),active,
            active && "APPROVED_FOR_SCOPE".equals(row.state()),row.uploadedAt());
    }

    private static String kind(String needCode) {
        if("OPEN-EVR".equals(needCode))return FleetCompositionRules.STOCK;
        if("OPEN-EV-SALES".equals(needCode))return FleetCompositionRules.SALES;
        throw invisible();
    }
    private static String metric(String kind) {
        return FleetCompositionRules.STOCK.equals(kind)?"stock":"sales";
    }


    private static Model findModel(Connection c,UUID id,UUID organization)throws SQLException{
        Model model=first(c,"""
            SELECT id,organization_id,manufacturer,model_name,variant,vehicle_class,
                   propulsion,recorded_at,classification_version FROM app_fleet_model_effective
            WHERE id=? AND organization_id=?
            """,FleetCompositionService::model,id,organization);
        if(model==null)throw invisible();
        return model;
    }

    private static CommandReceipt receipt(Connection c,UUID actor,String key,
            String action,byte[] fingerprint)throws SQLException {
        // Serialize retries from the same actor; separate payloads cannot share the key.
        first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
            r->true,"fleet-command:"+actor+":"+key);
        var previous=first(c,"""
            SELECT action,fingerprint,record_id FROM app_fleet_command
            WHERE actor_id=? AND command_key=?
            """,r->new CommandReceipt(r.getString(1),r.getBytes(2),
            r.getObject(3,UUID.class)),actor,key);
        if(previous!=null && (!action.equals(previous.action()) ||
                !java.security.MessageDigest.isEqual(fingerprint,previous.fingerprint())))
            throw new PilotProblem(409,"CHAVE_REUTILIZADA","Idempotency-Key",RULE,
                "Esta chave já identifica outro comando de frota.");
        return previous;
    }

    private static void remember(Connection c,UUID actor,String key,String action,
            byte[] fingerprint,UUID recordId)throws SQLException {
        update(c,"""
            INSERT INTO app_fleet_command(actor_id,command_key,action,fingerprint,record_id)
            VALUES (?,?,?,?,?)
            """,actor,key,action,fingerprint,recordId);
    }

    private static void addAlias(Connection c,UUID actor,UUID org,UUID model,Integer year,
            String recordKey)throws SQLException {
        if(year==null || year<1900 || year>2100)
            throw invalid("aliasYear","Alias precisa de ano de observação.");
        String key=normalizeKey(recordKey);
        UUID exists=first(c,"""
            SELECT id FROM app_fleet_model WHERE id=? AND organization_id=?
            """,r->r.getObject(1,UUID.class),model,org);
        if(exists==null)throw invisible();
        UUID inserted=first(c,"""
            INSERT INTO app_fleet_model_alias
                (organization_id,observation_year,record_key,model_id,mapped_by)
            VALUES (?,?,?,?,?) ON CONFLICT DO NOTHING RETURNING model_id
            """,r->r.getObject(1,UUID.class),org,year,key,model,actor);
        if(inserted==null){
            UUID prior=first(c,"""
                SELECT model_id FROM app_fleet_model_alias
                WHERE organization_id=? AND observation_year=? AND record_key=?
                """,r->r.getObject(1,UUID.class),org,year,key);
            if(!model.equals(prior))throw new PilotProblem(409,"ALIAS_CONFLITANTE",
                "sourceRecordKey",RULE,"Alias deste ano já pertence a outro modelo.");
        }
    }

    static String normalizeKey(String s) {
        if(s==null)throw invalid("sourceRecordKey","Alias ausente.");
        String normalized=s.strip().toUpperCase(Locale.ROOT);
        if(!normalized.matches("[A-Z0-9][A-Z0-9._-]{0,99}"))
            throw invalid("sourceRecordKey","Use a chave exata da fonte, sem espaço.");
        return normalized;
    }
    private static String label(String value,int max,String field){
        if(value==null || value.strip().isEmpty() || value.strip().length()>max
            || value.chars().anyMatch(Character::isISOControl))
            throw invalid(field,"Nome de fabricante/modelo/versão obrigatório.");
        return value.strip();
    }
    private static PilotProblem invisible(){
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","fleet",RULE,
            "Fonte, série ou organização não disponível para este usuário.");
    }
    private static PilotProblem invalid(String field,String message){
        return PilotProblem.invalid("SERIE_EVR_INVALIDA",field,RULE,message);
    }
}
