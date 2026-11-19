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
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Human-controlled PBEV ratings and comparator proposal workflow.
 * All numerical inputs originate in assessed OPEN-PBEV source candidates, never JSON.
 * Neither accepting a pair nor freezing a PREPARATORY snapshot authorizes E8.
 */
@ApplicationScoped
class VehicleRatingService {
    private static final String RULE="CF-OPEN-006";
    static final String SCENARIO="OPEN_LDV_DCFC_SIN_BR";
    record Model(UUID id,UUID organizationId,String title,String vehicleClass,String propulsion) {}
    record Source(UUID id,UUID organizationId,UUID licenseId,String sourceId,String edition,
                  byte[] sha,String state,String metadataState,Integer referenceYear,
                  OffsetDateTime purgedAt) {}
    record SourceOption(UUID id,UUID organizationId,String edition,String sha256,String state) {}
    record Fleet(UUID id,UUID organizationId,int year,Long denominator,String coverageState,
                 UUID licenseId,String sourceId,String edition,OffsetDateTime purgedAt) {}
    record FleetOption(UUID id,UUID organizationId,int year,long denominator,String coverageState) {}
    record Candidate(int row,String key,String metric,BigDecimal value,String unit,
                     int year,String geography,String locator) {}
    record Rating(UUID id,UUID organizationId,UUID modelId,UUID sourceObjectId,
                  UUID sourceLicenseId,int sourceRow,int year,String role,String fuel,
                  String cycle,int seats,String cycleEvidence,
                  BigDecimal originalValue,String originalUnit,
                  BigDecimal normalizedValue,String normalizedUnit,String metric,
                  String locator,String status,UUID createdBy,OffsetDateTime createdAt) {}
    record Register(UUID organizationId,UUID modelId,UUID sourceObjectId,Integer sourceRow,
                    String role,String fuel,String cycle,Integer seats,String cycleEvidence) {}
    record Propose(UUID organizationId,UUID fleetSnapshotId,UUID bevRatingId,
                   UUID fossilRatingId,String serviceDescription,String reason) {}
    record Review(String outcome,String reason) {}
    record Pair(UUID id,UUID organizationId,UUID fleetSnapshotId,UUID bevModelId,
                UUID bevRatingId,UUID fossilRatingId,String status,int version,String reason,
                String serviceDescription,UUID proposedBy,UUID reviewedBy,String reviewReason,
                OffsetDateTime createdAt) {}
    record Line(UUID modelId,String modelName,long evr,UUID pairId,
                BigDecimal kwh100mi,BigDecimal mpg,String cycle,String status) {}
    record Composition(UUID fleetSnapshotId,Integer year,Long total,
                       BigDecimal afecKwh100mi,BigDecimal mpgUs,String cycle,
                       List<Line> lines,List<String> issues,boolean canSavePreparatory,
                       boolean usableForE8) {}
    record Snapshot(UUID id,UUID organizationId,UUID fleetSnapshotId,int year,
                    String cycle,BigDecimal afecKwh100mi,BigDecimal mpgUs,Integer modelCount,
                    String status,OffsetDateTime createdAt,boolean usableForE8) {}
    record Board(UUID actorId,boolean canManage,boolean canReview,
                 List<Model> models,List<SourceOption> sources,List<FleetOption> fleets,
                 List<Rating> ratings,List<Pair> pairs,List<Snapshot> snapshots,
                 String guidance) {}
    private record Receipt(String action,byte[] fingerprint,UUID recordId) {}

    private final PilotDatabase db;
    private final PlatformSourceReadiness readiness;
    private final PlatformSourceWork managers;
    private final PlatformSourceFiles rights;
    private final Set<UUID> reviewers;

    VehicleRatingService(PilotDatabase db,PlatformSourceReadiness readiness,
            PlatformSourceWork managers,PlatformSourceFiles rights,
            @ConfigProperty(name="carbonflow.platform.vehicle-ratings.reviewers") Optional<String> roster){
        this.db=db;this.readiness=readiness;this.managers=managers;this.rights=rights;
        try{
            this.reviewers=roster.map(x->Arrays.stream(x.split(","))
                .map(String::strip).filter(value->!value.isEmpty()).map(UUID::fromString)
                .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        }catch(IllegalArgumentException ex){
            throw new IllegalStateException("Lista de revisores de ratings inválida.",ex);
        }
    }

    Board board(String token){
        return db.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            var orgs=actor.organizations();
            var scope=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
            try{
                var models=query(c,"""
                    SELECT id,organization_id,concat(manufacturer,' ',model_name,' ',variant),
                           vehicle_class,propulsion FROM app_fleet_model
                    WHERE organization_id=ANY(?) ORDER BY manufacturer,model_name LIMIT 200
                    """,r->new Model(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                        r.getString(3),r.getString(4),r.getString(5)),scope);
                var options=query(c,"""
                    SELECT o.id,o.organization_id,o.license_id,s.source_id,s.edition,
                           o.sha256,o.state,s.state,s.reference_year,o.purged_at
                    FROM app_platform_source_object o
                    JOIN app_platform_source_submission s ON s.id=o.submission_id
                    WHERE s.need_code='OPEN-PBEV' AND o.organization_id=ANY(?)
                    ORDER BY o.uploaded_at DESC LIMIT 60
                    """,VehicleRatingService::sourceRow,scope);
                var sources=new ArrayList<SourceOption>();
                for(var src:options){
                    if(sourceRights(c,src))sources.add(new SourceOption(src.id(),src.organizationId(),
                        src.edition(),java.util.HexFormat.of().formatHex(src.sha()),src.state()));
                }
                var availableFleet=query(c,"""
                    SELECT f.id,f.organization_id,f.observation_year,f.denominator,
                           f.coverage_state,i.source_license_id,s.source_id,s.edition,f.purged_at
                    FROM app_fleet_composition_snapshot f
                    JOIN app_fleet_ingestion i ON i.id=f.ingestion_id
                    JOIN app_platform_source_object o ON o.id=i.source_object_id
                    JOIN app_platform_source_submission s ON s.id=o.submission_id
                    WHERE f.organization_id=ANY(?) ORDER BY f.constructed_at DESC LIMIT 80
                    """,VehicleRatingService::fleetRow,scope);
                var fleets=new ArrayList<FleetOption>();
                for(var fleet:availableFleet)if(fleetRights(c,fleet))
                    fleets.add(new FleetOption(fleet.id(),fleet.organizationId(),fleet.year(),
                        fleet.denominator(),fleet.coverageState()));
                var allRatings=query(c,"""
                    SELECT id,organization_id,model_id,source_object_id,source_license_id,
                           source_row,year,role,fuel,cycle,seats,cycle_evidence,
                           original_value,original_unit,normalized_value,normalized_unit,
                           metric,locator,status,created_by,created_at
                    FROM app_vehicle_rating WHERE organization_id=ANY(?)
                    ORDER BY created_at DESC,id DESC LIMIT 400
                    """,VehicleRatingService::ratingRow,scope);
                var ratings=new ArrayList<Rating>();
                for(var rating:allRatings){
                    if(rating.status().equals("CANDIDATE") && ratingRights(c,rating))
                        ratings.add(rating);
                }
                var knownRatings=ratings.stream().map(Rating::id).collect(Collectors.toSet());
                var allPairs=query(c,"""
                    SELECT id,organization_id,fleet_snapshot_id,bev_model_id,
                           bev_rating_id,fossil_rating_id,status,version,reason,
                           service_description,proposed_by,reviewed_by,review_reason,created_at
                    FROM app_vehicle_comparable_pair WHERE organization_id=ANY(?)
                    ORDER BY created_at DESC,id DESC LIMIT 400
                    """,VehicleRatingService::pairRow,scope);
                var pairs=allPairs.stream().filter(pair->
                    knownRatings.contains(pair.bevRatingId()) &&
                    knownRatings.contains(pair.fossilRatingId()) &&
                    fleets.stream().anyMatch(f->f.id().equals(pair.fleetSnapshotId()))).toList();
                var existing=query(c,"""
                    SELECT id,organization_id,fleet_snapshot_id,year,cycle,
                           afec_kwh_per_100mi,mpg_us,model_count,status,constructed_at
                    FROM app_vehicle_rating_snapshot
                    WHERE organization_id=ANY(?) ORDER BY constructed_at DESC,id DESC LIMIT 100
                    """,VehicleRatingService::snapshotRow,scope);
                var snapshots=new ArrayList<Snapshot>();
                for(var snapshot:existing){
                    if(fleets.stream().noneMatch(f->f.id().equals(snapshot.fleetSnapshotId())))continue;
                    try{
                        snapshots.add(snapshot(c,snapshot.id(),orgs));
                    }catch(PilotProblem denied){
                        snapshots.add(new Snapshot(snapshot.id(),snapshot.organizationId(),
                            snapshot.fleetSnapshotId(),snapshot.year(),snapshot.cycle(),
                            null,null,null,"RIGHTS_UNAVAILABLE",snapshot.createdAt(),false));
                    }
                }
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_VEHICLE_RATINGS");
                return new Board(actor.id(),managers.canManage(actor.id()),reviewers.contains(actor.id()),
                    List.copyOf(models),List.copyOf(sources),List.copyOf(fleets),
                    List.copyOf(ratings),List.copyOf(pairs),snapshots,
                    "PBEV não define a frota EVR. Ratings, ciclo e comparáveis exigem parecer humano; snapshots não habilitam E8.");
            }finally{scope.free();}
        });
    }

    List<Candidate> candidates(String token,UUID sourceId){
        return db.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            Source s=source(c,actor.organizations(),sourceId);
            requireSourceRights(c,s);
            var rows=query(c,"""
                SELECT row_index,record_key,metric,value_numeric,unit,period_year,geography,locator
                FROM app_platform_source_candidate WHERE object_id=?
                ORDER BY row_index LIMIT 2000
                """,r->new Candidate(r.getInt(1),r.getString(2),r.getString(3),
                    r.getBigDecimal(4),r.getString(5),r.getInt(6),r.getString(7),
                    r.getString(8)),sourceId);
            ComplianceStaff.audit(c,actor.id(),null,"VIEW_VEHICLE_RATINGS");
            return List.copyOf(rows);
        });
    }

    Rating register(String token,String key,Register input){
        return db.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(input==null || input.organizationId()==null ||
               !actor.organizations().contains(input.organizationId()))throw hidden();
            if(input.sourceObjectId()==null || input.modelId()==null ||
               input.sourceRow()==null || input.seats()==null ||
               input.seats()<1 || input.seats()>80 ||
               !Set.of("URBAN","HIGHWAY","COMBINED").contains(Objects.toString(input.cycle(),"")))
                throw invalid("rating","Informe modelo, linha, ciclo e capacidade verificados.");
            String evidence=required(input.cycleEvidence(),5,300,"cycleEvidence");
            if(!Set.of("BEV","FOSSIL").contains(Objects.toString(input.role(),"")))
                throw invalid("role","Informe BEV ou FOSSIL.");
            if(!Set.of("ELECTRICITY","GASOLINE","DIESEL","ETHANOL","FLEX").contains(Objects.toString(input.fuel(),"")))
                throw invalid("fuel","Combustível não suportado.");
            if(("BEV".equals(input.role()) && !"ELECTRICITY".equals(input.fuel())) ||
               ("FOSSIL".equals(input.role()) && "ELECTRICITY".equals(input.fuel())))
                throw invalid("fuel","Tipo de combustível incompatível com o rating.");
            Source src=source(c,actor.organizations(),input.sourceObjectId());
            if(!src.organizationId().equals(input.organizationId()))throw hidden();
            requireSourceRights(c,src);
            Model model=oneModel(c,input.modelId(),input.organizationId());
            if(!"LDV".equals(model.vehicleClass()) ||
               ("BEV".equals(input.role()) != "BEV".equals(model.propulsion())) ||
               ("FOSSIL".equals(input.role()) && !"OTHER".equals(model.propulsion())))
                throw invalid("modelId","Escolha veículo leve com propulsão compatível; não inferir BEV.");
            Candidate candidate=first(c,"""
                SELECT row_index,record_key,metric,value_numeric,unit,period_year,geography,locator
                FROM app_platform_source_candidate WHERE object_id=? AND row_index=?
                """,r->new Candidate(r.getInt(1),r.getString(2),r.getString(3),
                    r.getBigDecimal(4),r.getString(5),r.getInt(6),r.getString(7),
                    r.getString(8)),src.id(),input.sourceRow());
            if(candidate==null || !"BR".equals(candidate.geography()) ||
               !("BEV".equals(input.role())?"ev_energy":"fuel_economy").equals(candidate.metric()) ||
               src.referenceYear()!=null && src.referenceYear()!=candidate.year())
                throw invalid("sourceRow","Linha, grandeza, ano e geografia não são compatíveis com a fonte PBEV.");
            Boolean accepted=first(c,"""
                SELECT true FROM app_platform_source_assessment
                WHERE object_id=? AND outcome='APPROVED_FOR_SCOPE'
                  AND methodology='VM0038' AND methodology_edition='1.1'
                  AND scenario=? AND blocked_capability='BASELINE_BE'
                  AND metric=? AND period_year=? AND geography='BR' AND unit=? LIMIT 1
                """,r->true,src.id(),SCENARIO,candidate.metric(),candidate.year(),candidate.unit());
            if(!Boolean.TRUE.equals(accepted))
                throw new PilotProblem(409,"DADOS_NAO_ADMITIDOS","sourceRow",RULE,
                    "É necessário parecer independente da linha, edição, métrica e unidade.");
            VehicleRatingsMath.Converted normalized;
            try{normalized=VehicleRatingsMath.convert(input.role(),candidate.unit(),candidate.value());}
            catch(IllegalArgumentException ex){throw invalid("unit",ex.getMessage());}
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("RATING|"+input);
            var replay=receipt(c,actor.id(),command,"REGISTER_RATING",fingerprint);
            if(replay!=null)return rating(c,replay.recordId(),actor.organizations());
            UUID id=UUID.randomUUID();
            var inserted=first(c,"""
                INSERT INTO app_vehicle_rating
                  (id,organization_id,model_id,source_object_id,source_license_id,
                   source_row,source_sha256,year,role,fuel,cycle,seats,cycle_evidence,
                   original_value,original_unit,normalized_value,normalized_unit,metric,locator,created_by)
                VALUES(?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """,r->r.getObject(1,UUID.class),id,input.organizationId(),model.id(),src.id(),
                src.licenseId(),candidate.row(),src.sha(),candidate.year(),input.role(),
                input.fuel(),input.cycle(),input.seats(),evidence,candidate.value(),
                candidate.unit(),normalized.value(),normalized.unit(),candidate.metric(),
                candidate.locator(),actor.id());
            if(inserted==null)throw new PilotProblem(409,"RATING_DUPLICADO","sourceRow",RULE,
                "A mesma linha/modelo/ciclo já foi registrada. Use a versão existente.");
            remember(c,actor.id(),command,"REGISTER_RATING",fingerprint,id);
            ComplianceStaff.audit(c,actor.id(),null,"REGISTER_VEHICLE_RATING");
            return rating(c,id,actor.organizations());
        });
    }

    Pair propose(String token,String key,Propose input){
        return db.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(input==null || input.organizationId()==null ||
              !actor.organizations().contains(input.organizationId()) ||
              input.fleetSnapshotId()==null ||
              input.bevRatingId()==null || input.fossilRatingId()==null)throw hidden();
            String reason=required(input.reason(),20,1500,"reason"),
                service=required(input.serviceDescription(),10,250,"serviceDescription");
            Fleet fleet=fleet(c,actor.organizations(),input.fleetSnapshotId());
            if(!fleet.organizationId().equals(input.organizationId()))throw hidden();
            requireFleetRights(c,fleet);
            Rating ev=rating(c,input.bevRatingId(),actor.organizations());
            Rating fossil=rating(c,input.fossilRatingId(),actor.organizations());
            if(!ev.organizationId().equals(input.organizationId()) ||
                !fossil.organizationId().equals(input.organizationId()))throw hidden();
            if(!"BEV".equals(ev.role()) || !"FOSSIL".equals(fossil.role()) ||
                ev.modelId().equals(fossil.modelId()))
                throw invalid("pair","Selecione um BEV e um fóssil distintos.");
            boolean inFleet=first(c,"""
                SELECT true FROM app_fleet_composition_weight
                WHERE snapshot_id=? AND model_id=? LIMIT 1
                """,r->true,fleet.id(),ev.modelId())!=null;
            if(!inFleet)throw invalid("bevRatingId","BEV não consta do vetor EVR selecionado.");
            String command=PilotCommands.key(key);
            byte[] fp=AccessSecrets.digest("PAIR|"+input);
            var replay=receipt(c,actor.id(),command,"PROPOSE_PAIR",fp);
            if(replay!=null)return pair(c,replay.recordId(),actor.organizations());
            UUID id=UUID.randomUUID();
            UUID inserted=first(c,"""
                INSERT INTO app_vehicle_comparable_pair
                 (id,organization_id,fleet_snapshot_id,bev_model_id,
                  bev_rating_id,fossil_rating_id,reason,service_description,proposed_by)
                VALUES(?,?,?,?,?,?,?,?,?)
                ON CONFLICT DO NOTHING RETURNING id
                """,r->r.getObject(1,UUID.class),id,input.organizationId(),fleet.id(),
                ev.modelId(),ev.id(),fossil.id(),reason,service,actor.id());
            if(inserted==null)throw new PilotProblem(409,"PAR_JA_PROPOSTO","pair",RULE,
                "Este par já foi proposto para a mesma frota.");
            remember(c,actor.id(),command,"PROPOSE_PAIR",fp,id);
            ComplianceStaff.audit(c,actor.id(),null,"PROPOSE_VEHICLE_PAIR");
            return pair(c,id,actor.organizations());
        });
    }

    Pair review(String token,UUID id,String ifMatch,Review review){
        return db.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            if(!reviewers.contains(actor.id()))throw hidden();
            if(review==null || !Set.of("ACCEPTED","REJECTED").contains(Objects.toString(review.outcome(),"")))
                throw invalid("outcome","Escolha aceitar ou rejeitar.");
            String reason=required(review.reason(),15,1500,"reason");
            Pair pair=pair(c,id,actor.organizations());
            if(PilotCommands.expected(ifMatch)!=pair.version())
                throw new PilotProblem(412,"VERSAO_DIVERGENTE","If-Match",RULE,
                    "A revisão já foi concluída ou alterada.");
            if(!"PROPOSED".equals(pair.status()))throw new PilotProblem(409,
                "PARECER_JA_REGISTRADO","pair",RULE,"O parecer é imutável.");
            Rating ev=rating(c,pair.bevRatingId(),actor.organizations());
            Rating fossil=rating(c,pair.fossilRatingId(),actor.organizations());
            if(actor.id().equals(pair.proposedBy()) || actor.id().equals(ev.createdBy()) ||
                    actor.id().equals(fossil.createdBy()))
                throw new PilotProblem(403,"REVISAO_NAO_INDEPENDENTE","reviewer",RULE,
                    "O proponente ou cadastrador dos ratings não pode revisar o próprio trabalho.");
            Fleet fleet=fleet(c,actor.organizations(),pair.fleetSnapshotId());
            requireFleetRights(c,fleet);
            if("ACCEPTED".equals(review.outcome())){
                requireComparable(c,fleet,ev,fossil);
                // Only one current comparison per BEV/fleet; earlier reviewed decisions survive.
                first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                    r->true,"rating-pair:"+fleet.id()+":"+pair.bevModelId());
                update(c,"""
                    UPDATE app_vehicle_comparable_pair SET status='SUPERSEDED'
                    WHERE fleet_snapshot_id=? AND bev_model_id=? AND status='ACCEPTED'
                    """,fleet.id(),pair.bevModelId());
                update(c,"""
                    UPDATE app_vehicle_rating_snapshot
                       SET status='STALE_REFERENCE'
                    WHERE fleet_snapshot_id=? AND status='PREPARATORY'
                    """,fleet.id());
            }
            var applied=first(c,"""
                UPDATE app_vehicle_comparable_pair
                   SET status=?,version=2,reviewed_by=?,review_reason=?,
                       reviewed_at=statement_timestamp()
                WHERE id=? AND version=1 AND status='PROPOSED'
                RETURNING id
                """,r->r.getObject(1,UUID.class),review.outcome(),actor.id(),reason,id);
            if(applied==null)throw new PilotProblem(409,"PARECER_CONCORRENTE","pair",RULE,
                "Outra pessoa revisou este par.");
            update(c,"""
                INSERT INTO app_vehicle_pair_review_event
                (pair_id,version,outcome,reviewed_by,reason)
                VALUES (?,2,?,?,?)
                """,id,review.outcome(),actor.id(),reason);
            ComplianceStaff.audit(c,actor.id(),null,"REVIEW_VEHICLE_PAIR");
            return pair(c,id,actor.organizations());
        });
    }

    Composition composition(String token,UUID fleetId){
        return db.transaction(c->{
            var actor=readiness.requireViewer(c,token);
            Composition view=compose(c,actor.organizations(),fleetId);
            ComplianceStaff.audit(c,actor.id(),null,"VIEW_VEHICLE_RATING_COMPOSITION");
            return view;
        });
    }

    Snapshot freeze(String token,String key,UUID fleetId){
        return db.transaction(c->{
            var actor=managers.requireManager(c,token);
            var command=PilotCommands.key(key);
            byte[] fp=AccessSecrets.digest("FREEZE|"+fleetId);
            var replay=receipt(c,actor.id(),command,"CREATE_RATING_SNAPSHOT",fp);
            if(replay!=null)return snapshot(c,replay.recordId(),actor.organizations());
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",
                r->true,"ratings-freeze:"+fleetId);
            Composition view=compose(c,actor.organizations(),fleetId);
            if(!view.canSavePreparatory())throw new PilotProblem(409,
                "RATINGS_INCOMPLETOS","fleetSnapshotId",RULE,
                "Faltam pares aceitos, direitos, vintages ou um ciclo homogêneo.");
            Fleet fleet=fleet(c,actor.organizations(),fleetId);
            String signature=fleetId+"|"+view.cycle()+"|"+
                view.lines().stream().map(l->l.modelId()+":"+l.pairId()).sorted().toList();
            byte[] digest=AccessSecrets.digest(signature);
            UUID found=first(c,"""
                SELECT id FROM app_vehicle_rating_snapshot
                WHERE fleet_snapshot_id=? AND lineage_digest=?
                """,r->r.getObject(1,UUID.class),fleetId,digest);
            if(found!=null){
                remember(c,actor.id(),command,"CREATE_RATING_SNAPSHOT",fp,found);
                return snapshot(c,found,actor.organizations());
            }
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_vehicle_rating_snapshot
                 (id,organization_id,fleet_snapshot_id,year,cycle,lineage_digest,
                  afec_kwh_per_100mi,mpg_us,model_count,constructed_by)
                VALUES(?,?,?,?,?,?,?,?,?,?)
                """,id,fleet.organizationId(),fleetId,view.year(),view.cycle(),digest,
                view.afecKwh100mi(),view.mpgUs(),view.lines().size(),actor.id());
            for(var line:view.lines()){
                update(c,"""
                    INSERT INTO app_vehicle_rating_snapshot_line
                     (snapshot_id,organization_id,model_id,pair_id,evr,
                      ev_kwh_per_100mi,fossil_mpg_us)
                    VALUES (?,?,?,?,?,?,?)
                    """,id,fleet.organizationId(),line.modelId(),line.pairId(),
                    line.evr(),line.kwh100mi(),line.mpg());
            }
            remember(c,actor.id(),command,"CREATE_RATING_SNAPSHOT",fp,id);
            ComplianceStaff.audit(c,actor.id(),null,"CREATE_VEHICLE_RATING_SNAPSHOT");
            return snapshot(c,id,actor.organizations());
        });
    }

    private Composition compose(Connection c,Set<UUID> orgs,UUID id)throws SQLException{
        Fleet fleet=fleet(c,orgs,id);
        requireFleetRights(c,fleet);
        var count=first(c,"""
            SELECT count(*) FROM app_fleet_composition_weight WHERE snapshot_id=?
            """,r->r.getInt(1),id);
        var weights=query(c,"""
            SELECT w.model_id,concat(m.manufacturer,' ',m.model_name,' ',m.variant),
                   w.observed_stock
            FROM app_fleet_composition_weight w
            JOIN app_fleet_model m ON m.id=w.model_id
            WHERE w.snapshot_id=? ORDER BY w.model_id LIMIT 2001
            """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2),r.getLong(3)},id);
        var lines=new ArrayList<Line>();
        var issues=new ArrayList<String>();
        var included=new ArrayList<VehicleRatingsMath.WeightedRow>();
        if(count==null || count==0 || count!=weights.size())
            issues.add("Vetor EVR indisponível ou excede o limite; não inferir veículos.");
        for(var w:weights){
            UUID modelId=(UUID)w[0];
            String name=(String)w[1];
            long evr=(Long)w[2];
            var matches=query(c,"""
                SELECT id,organization_id,fleet_snapshot_id,bev_model_id,
                       bev_rating_id,fossil_rating_id,status,version,reason,
                       service_description,proposed_by,reviewed_by,review_reason,created_at
                FROM app_vehicle_comparable_pair
                WHERE fleet_snapshot_id=? AND bev_model_id=? AND status='ACCEPTED'
                ORDER BY reviewed_at DESC,id DESC LIMIT 2
                """,VehicleRatingService::pairRow,id,modelId);
            if(matches.size()!=1){
                issues.add(name+": "+(matches.isEmpty()?"sem par aprovado por revisor":"pares aceitos conflitantes"));
                lines.add(new Line(modelId,name,evr,null,null,null,null,"PENDING_PAIR"));
                continue;
            }
            Pair pair=matches.getFirst();
            Rating ev,ff;
            try{
                ev=rating(c,pair.bevRatingId(),orgs);
                ff=rating(c,pair.fossilRatingId(),orgs);
                requireComparable(c,fleet,ev,ff);
            }catch(PilotProblem problem){
                issues.add(name+": direitos ou comparabilidade precisam de revisão");
                lines.add(new Line(modelId,name,evr,pair.id(),null,null,null,"PENDING_SOURCE"));
                continue;
            }
            included.add(new VehicleRatingsMath.WeightedRow(modelId,evr,
                ev.normalizedValue(),ff.normalizedValue(),ev.cycle(),ff.fuel()));
            lines.add(new Line(modelId,name,evr,pair.id(),ev.normalizedValue(),
                ff.normalizedValue(),ev.cycle(),"ACCEPTED_INTERNAL"));
        }
        var calculated=VehicleRatingsMath.compose(included);
        issues.addAll(calculated.issues());
        boolean complete=issues.isEmpty() && lines.size()==weights.size() &&
            calculated.valid() && fleet.denominator()!=null &&
            calculated.evrTotal().equals(fleet.denominator());
        if(!complete && issues.isEmpty())issues.add("Vetores de rating e EVR não correspondem ao mesmo denominador.");
        return new Composition(id,fleet.year(),complete?calculated.evrTotal():null,
            complete?calculated.afecKwhPer100mi():null,
            complete?calculated.mpgUs():null,
            complete?calculated.cycle():null,List.copyOf(lines),List.copyOf(issues),
            complete,false);
    }

    private void requireComparable(Connection c,Fleet fleet,Rating ev,Rating ff)
        throws SQLException {
        Model evModel=oneModel(c,ev.modelId(),fleet.organizationId());
        Model fossilModel=oneModel(c,ff.modelId(),fleet.organizationId());
        if(!"BEV".equals(ev.role()) || !"FOSSIL".equals(ff.role()) ||
           !"BEV".equals(evModel.propulsion()) || !"OTHER".equals(fossilModel.propulsion()) ||
           !"LDV".equals(evModel.vehicleClass()) || !"LDV".equals(fossilModel.vehicleClass()) ||
           ev.modelId().equals(ff.modelId()) || !ev.cycle().equals(ff.cycle()) ||
           ev.year()!=fleet.year() || ff.year()!=fleet.year() ||
           Math.abs(ev.seats()-ff.seats())>1 || !"GASOLINE".equals(ff.fuel()))
            throw new PilotProblem(409,"COMPARABILIDADE_PENDENTE","pair",RULE,
                "Ciclo, categoria, serviço/capacidade, ano ou combustível não comparáveis; requer rota independente.");
    }

    private Source source(Connection c,Set<UUID> orgs,UUID id)throws SQLException{
        var array=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            var source=first(c,"""
                SELECT o.id,o.organization_id,o.license_id,s.source_id,s.edition,
                       o.sha256,o.state,s.state,s.reference_year,o.purged_at
                FROM app_platform_source_object o
                JOIN app_platform_source_submission s ON s.id=o.submission_id
                JOIN app_methodology_source_need n ON n.code=s.need_code
                WHERE o.id=? AND o.organization_id=ANY(?)
                  AND n.methodology='VM0038' AND n.methodology_edition='1.1'
                  AND n.scenario=? AND n.code='OPEN-PBEV'
                """,VehicleRatingService::sourceRow,id,array,SCENARIO);
            if(source==null)throw hidden();
            return source;
        }finally{array.free();}
    }
    private static Source sourceRow(java.sql.ResultSet r)throws SQLException{
        return new Source(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getString(4),r.getString(5),r.getBytes(6),
            r.getString(7),r.getString(8),(Integer)r.getObject(9),
            r.getObject(10,OffsetDateTime.class));
    }
    private boolean sourceRights(Connection c,Source s)throws SQLException{
        return s.purgedAt()==null && "METADATA_ACCEPTED".equals(s.metadataState()) &&
           "APPROVED_FOR_SCOPE".equals(s.state()) &&
           rights.granted(c,Set.of(s.organizationId()),s.sourceId(),s.edition(),
               s.licenseId(),"EXTRACT_TEXT")!=null &&
           rights.granted(c,Set.of(s.organizationId()),s.sourceId(),s.edition(),
               s.licenseId(),"SHOW_EXCERPTS")!=null &&
           rights.granted(c,Set.of(s.organizationId()),s.sourceId(),s.edition(),
               s.licenseId(),"EXPORT_DERIVATIVES")!=null;
    }
    private void requireSourceRights(Connection c,Source s)throws SQLException{
        if(!sourceRights(c,s))throw new PilotProblem(403,"DIREITOS_PBEV_PENDENTES","source",RULE,
            "Fonte PBEV exige parecer e licenças vigentes de extração, exibição e derivados.");
    }

    private Fleet fleet(Connection c,Set<UUID> orgs,UUID id)throws SQLException{
        var array=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            Fleet fleet=first(c,"""
                SELECT f.id,f.organization_id,f.observation_year,f.denominator,
                       f.coverage_state,i.source_license_id,s.source_id,s.edition,f.purged_at
                FROM app_fleet_composition_snapshot f
                JOIN app_fleet_ingestion i ON i.id=f.ingestion_id
                JOIN app_platform_source_object o ON o.id=i.source_object_id
                JOIN app_platform_source_submission s ON s.id=o.submission_id
                WHERE f.id=? AND f.organization_id=ANY(?) AND i.data_kind='STOCK_IN_CIRCULATION'
                """,VehicleRatingService::fleetRow,id,array);
            if(fleet==null)throw hidden();
            return fleet;
        }finally{array.free();}
    }
    private static Fleet fleetRow(java.sql.ResultSet r)throws SQLException{
        return new Fleet(r.getObject(1,UUID.class),r.getObject(2,UUID.class),r.getInt(3),
            (Long)r.getObject(4),r.getString(5),r.getObject(6,UUID.class),
            r.getString(7),r.getString(8),r.getObject(9,OffsetDateTime.class));
    }
    private boolean fleetRights(Connection c,Fleet fleet)throws SQLException{
        return fleet.purgedAt()==null && fleet.denominator()!=null &&
            rights.granted(c,Set.of(fleet.organizationId()),fleet.sourceId(),fleet.edition(),
                fleet.licenseId(),"SHOW_EXCERPTS")!=null &&
            rights.granted(c,Set.of(fleet.organizationId()),fleet.sourceId(),fleet.edition(),
                fleet.licenseId(),"EXPORT_DERIVATIVES")!=null;
    }
    private void requireFleetRights(Connection c,Fleet fleet)throws SQLException{
        if(!fleetRights(c,fleet))throw new PilotProblem(403,"FROTA_SEM_DIREITOS","fleet",RULE,
            "Fonte EVR expirada ou não autorizada para derivados.");
    }

    private Model oneModel(Connection c,UUID id,UUID org)throws SQLException{
        var model=first(c,"""
            SELECT id,organization_id,concat(manufacturer,' ',model_name,' ',variant),
                   vehicle_class,propulsion FROM app_fleet_model
            WHERE id=? AND organization_id=?
            """,r->new Model(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getString(3),r.getString(4),r.getString(5)),id,org);
        if(model==null)throw hidden();
        return model;
    }

    private Rating rating(Connection c,UUID id,Set<UUID> orgs)throws SQLException{
        var array=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            Rating r=first(c,"""
                SELECT id,organization_id,model_id,source_object_id,source_license_id,
                       source_row,year,role,fuel,cycle,seats,cycle_evidence,
                       original_value,original_unit,normalized_value,normalized_unit,
                       metric,locator,status,created_by,created_at
                FROM app_vehicle_rating WHERE id=? AND organization_id=ANY(?)
                """,VehicleRatingService::ratingRow,id,array);
            if(r==null || !"CANDIDATE".equals(r.status()) || !ratingRights(c,r))throw hidden();
            return r;
        }finally{array.free();}
    }
    private static Rating ratingRow(java.sql.ResultSet r)throws SQLException{
        return new Rating(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),
            r.getObject(5,UUID.class),r.getInt(6),r.getInt(7),
            r.getString(8),r.getString(9),r.getString(10),r.getInt(11),
            r.getString(12),r.getBigDecimal(13),r.getString(14),
            r.getBigDecimal(15),r.getString(16),r.getString(17),
            r.getString(18),r.getString(19),r.getObject(20,UUID.class),
            r.getObject(21,OffsetDateTime.class));
    }
    private boolean ratingRights(Connection c,Rating r)throws SQLException{
        Source s=source(c,Set.of(r.organizationId()),r.sourceObjectId());
        return s.licenseId().equals(r.sourceLicenseId()) && sourceRights(c,s);
    }

    private Pair pair(Connection c,UUID id,Set<UUID> orgs)throws SQLException{
        var array=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            var pair=first(c,"""
                SELECT id,organization_id,fleet_snapshot_id,bev_model_id,
                       bev_rating_id,fossil_rating_id,status,version,reason,
                       service_description,proposed_by,reviewed_by,review_reason,created_at
                FROM app_vehicle_comparable_pair WHERE id=? AND organization_id=ANY(?)
                """,VehicleRatingService::pairRow,id,array);
            if(pair==null)throw hidden();
            // Do not expose a pair whose underlying protected ratings have expired.
            rating(c,pair.bevRatingId(),orgs);
            rating(c,pair.fossilRatingId(),orgs);
            return pair;
        }finally{array.free();}
    }
    private static Pair pairRow(java.sql.ResultSet r)throws SQLException{
        return new Pair(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getObject(4,UUID.class),
            r.getObject(5,UUID.class),r.getObject(6,UUID.class),r.getString(7),
            r.getInt(8),r.getString(9),r.getString(10),
            r.getObject(11,UUID.class),r.getObject(12,UUID.class),
            r.getString(13),r.getObject(14,OffsetDateTime.class));
    }

    private Snapshot snapshot(Connection c,UUID id,Set<UUID> orgs)throws SQLException{
        var array=c.createArrayOf("uuid",orgs.toArray(UUID[]::new));
        try{
            Snapshot result=first(c,"""
                SELECT id,organization_id,fleet_snapshot_id,year,cycle,
                       afec_kwh_per_100mi,mpg_us,model_count,status,constructed_at
                FROM app_vehicle_rating_snapshot
                WHERE id=? AND organization_id=ANY(?)
                """,VehicleRatingService::snapshotRow,id,array);
            if(result==null || "SUSPENDED".equals(result.status()))throw hidden();
            Fleet fleet=fleet(c,orgs,result.fleetSnapshotId());
            requireFleetRights(c,fleet);
            var sources=query(c,"""
                SELECT DISTINCT p.bev_rating_id,p.fossil_rating_id
                FROM app_vehicle_rating_snapshot_line l
                JOIN app_vehicle_comparable_pair p ON p.id=l.pair_id
                WHERE l.snapshot_id=?
                """,r->new UUID[]{r.getObject(1,UUID.class),r.getObject(2,UUID.class)},id);
            for(var pair:sources){
                rating(c,pair[0],orgs);
                rating(c,pair[1],orgs);
            }
            return result;
        }finally{array.free();}
    }
    private static Snapshot snapshotRow(java.sql.ResultSet r)throws SQLException{
        return new Snapshot(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
            r.getObject(3,UUID.class),r.getInt(4),r.getString(5),
            r.getBigDecimal(6),r.getBigDecimal(7),(Integer)r.getObject(8),
            r.getString(9),r.getObject(10,OffsetDateTime.class),false);
    }

    private Receipt receipt(Connection c,UUID actor,String key,String action,byte[] fp)
        throws SQLException{
        first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,
            "vehicle-rating-command:"+actor+":"+key);
        Receipt found=first(c,"""
            SELECT action,fingerprint,record_id FROM app_vehicle_rating_command
            WHERE actor_id=? AND command_key=?
            """,r->new Receipt(r.getString(1),r.getBytes(2),
                r.getObject(3,UUID.class)),actor,key);
        if(found!=null && (!found.action().equals(action) ||
            !java.security.MessageDigest.isEqual(found.fingerprint(),fp)))
            throw new PilotProblem(409,"CHAVE_REUTILIZADA","Idempotency-Key",RULE,
                "Chave já usada para outro payload.");
        return found;
    }
    private void remember(Connection c,UUID actor,String key,String action,byte[] fp,UUID id)
        throws SQLException{
        update(c,"""
            INSERT INTO app_vehicle_rating_command(actor_id,command_key,action,fingerprint,record_id)
            VALUES (?,?,?,?,?)
            """,actor,key,action,fp,id);
    }

    private static String required(String value,int min,int max,String field){
        if(value==null || value.strip().length()<min || value.strip().length()>max ||
           value.chars().anyMatch(Character::isISOControl))
            throw invalid(field,"Informe fundamentação e localizador verificável.");
        return value.strip();
    }
    private static PilotProblem invalid(String field,String detail){
        return PilotProblem.invalid("PBEV_RATING_INVALIDO",field,RULE,detail);
    }
    private static PilotProblem hidden(){
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","rating",RULE,
            "Fonte, modelo ou projeto não disponível a esta identidade.");
    }
}
