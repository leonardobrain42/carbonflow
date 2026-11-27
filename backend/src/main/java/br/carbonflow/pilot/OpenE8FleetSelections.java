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
import java.util.HexFormat;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Explicit STAFF handoff of licensed catalogue REFERENCES into a project.
 * No licensed EVR, weight, AFEC, MPG, PBEV rating or derivative is copied.
 * A handoff is never parameter admission, an E8 run or rights to redistribute.
 *
 * Publishing requires MFA + platform source manager authority, independently
 * of a customer's project grants; reading the published metadata requires a
 * project monitoring grant. Both sides are scoped to the SAME organization.
 */
@ApplicationScoped
class OpenE8FleetSelections {
    private static final String RULE="CF-OPEN-008";
    record Option(String projectId,String period,UUID organizationId,
                  UUID energyBindingId,int energyBindingVersion,int year,
                  int currentSelectionVersion) {}
    record Input(String projectId,String period,UUID energyBindingId,
                 UUID ratingSnapshotId) {}
    record View(UUID id,String projectId,String period,int version,
                UUID energyBindingId,UUID fleetSnapshotId,UUID ratingSnapshotId,
                int observationYear,String cycle,String fleetSourceSha256,
                String ratingLineageSha256,String selectionSha256,String state,
                boolean stale,boolean usableForE8,OffsetDateTime publishedAt,
                List<String> gates) {}

    private record Target(UUID org,String state,String environment) {}
    private record Energy(UUID id,String period,int version,UUID organization,int year,
                          String sha) {}
    record Fleet(UUID fleetId,UUID ratingId,int year,int fleetYear,
                         String cycle,String fleetSha,String ratingSha,
                         String fleetCoverage,String ratingState,String stockState,
                         String stockKind,String geography,Long denominator,
                         Integer fleetModels,Integer ratingModels,
                         OffsetDateTime fleetPurged,OffsetDateTime ratingPurged,
                         OffsetDateTime ingestionPurged) {}
    record Coverage(long stockRows,long ratingRows,long stockTotal,
                            long ratingTotal,long mismatched,long acceptedPairs) {}
    private record Stored(UUID id,String project,String period,int version,
                          UUID organization,UUID energy,UUID fleet,UUID rating,
                          int year,String cycle,String fleetSha,String ratingSha,
                          String digest,OffsetDateTime created) {}

    private final PilotDatabase database;
    private final PlatformSourceWork managers;
    private final DynamicProjectScope scope;

    OpenE8FleetSelections(PilotDatabase database,PlatformSourceWork managers,
                          DynamicProjectScope scope) {
        this.database=database;this.managers=managers;this.scope=scope;
    }

    List<Option> options(String token) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(actor.organizations().isEmpty())return List.of();
            var organizations=c.createArrayOf("uuid",
                actor.organizations().toArray(UUID[]::new));
            try{
                var candidates=query(c,"""
                    SELECT p.id,p.organization_id,b.period_code,b.id,b.version,
                      min(extract(year from h.hour_start AT TIME ZONE 'UTC'))::int,
                      max(extract(year from h.hour_start AT TIME ZONE 'UTC'))::int
                    FROM app_project p
                    JOIN app_open_e8_input_binding_candidate b
                      ON b.project_id=p.id
                    JOIN app_open_e8_input_binding_hour h
                      ON h.project_id=b.project_id AND h.binding_id=b.id
                    WHERE p.organization_id=ANY(?) AND p.environment='SANDBOX'
                      AND p.state='DRAFT' AND b.state='CANDIDATE_NOT_ADMITTED'
                      AND b.version=(SELECT max(v.version)
                        FROM app_open_e8_input_binding_candidate v
                        WHERE v.project_id=b.project_id AND v.period_code=b.period_code)
                    GROUP BY p.id,p.organization_id,b.period_code,b.id,b.version
                    ORDER BY p.id,b.period_code LIMIT 150
                    """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),
                        r.getString(3),r.getObject(4,UUID.class),
                        r.getInt(5),r.getInt(6),r.getInt(7)},organizations);
                var result=new ArrayList<Option>();
                for(Object[] row:candidates){
                    String project=(String)row[0],period=(String)row[2];
                    UUID id=(UUID)row[3];
                    if(!row[5].equals(row[6]))continue;
                    if(!"CANDIDATE_NOT_ADMITTED".equals(
                            OpenE8InputBindings.state(c,project,period,id)))continue;
                    if(ProjectJourney.profile(ProjectGuides.candidate(c,project))
                        !=ProjectJourney.VM0038)continue;
                    Integer selected=first(c,"""
                        SELECT coalesce(max(version),0)
                        FROM app_open_e8_fleet_selection
                        WHERE project_id=? AND period_code=?
                        """,r->r.getInt(1),project,period);
                    result.add(new Option(project,period,(UUID)row[1],id,
                        (Integer)row[4],(Integer)row[5],selected));
                }
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_OPEN_E8_FLEET_PROJECT_OPTIONS");
                return List.copyOf(result);
            }finally{organizations.free();}
        });
    }

    List<View> list(String token,String project,String period) {
        return scope.read(token,project,"VIEW_OPEN_E8_FLEET_SELECTION",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            var stored=query(c,SELECT+"""
                WHERE project_id=? AND period_code=?
                ORDER BY version DESC LIMIT 20
                """,OpenE8FleetSelections::stored,project,code);
            var out=new ArrayList<View>();
            for(var record:stored)out.add(view(c,record));
            return List.copyOf(out);
        });
    }

    View detail(String token,String project,String period,UUID id) {
        return scope.read(token,project,"VIEW_OPEN_E8_FLEET_SELECTION",(c,ctx)->{
            String code=OpenMonitoringDossiers.code(period);
            Stored stored=first(c,SELECT+"""
                WHERE project_id=? AND period_code=? AND id=?
                """,OpenE8FleetSelections::stored,project,code,id);
            if(stored==null)throw hidden();
            return view(c,stored);
        });
    }

    View assign(String token,String key,String match,Input input) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(input==null || input.projectId()==null ||
               input.energyBindingId()==null || input.ratingSnapshotId()==null)
                throw bad("input","Selecione projeto, período, pacote ED/EC/EFel e snapshot de ratings.");
            String period=OpenMonitoringDossiers.code(input.period());
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("OPEN-E8-FLEET-HANDOFF-V1|"+
                input.projectId()+"|"+period+"|"+expected+"|"+
                input.energyBindingId()+"|"+input.ratingSnapshotId());
            Object[] replay=first(c,"""
                SELECT project_id,selection_id,fingerprint
                FROM app_open_e8_fleet_selection_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),
                    r.getBytes(3)},actor.id(),command);
            if(replay!=null){
                if(!input.projectId().equals(replay[0]) ||
                   !MessageDigest.isEqual(fingerprint,(byte[])replay[2]))
                    throw conflict("Chave de idempotência foi reutilizada com outro vínculo.");
                Stored previous=first(c,SELECT+"""
                    WHERE project_id=? AND id=?
                    """,OpenE8FleetSelections::stored,input.projectId(),(UUID)replay[1]);
                if(previous==null)throw hidden();
                return view(c,previous);
            }

            // Lock the same project row used by DynamicProjectScope.write to
            // serialize writes, even though publishing comes from MFA staff.
            Target project=first(c,"""
                SELECT organization_id,state,environment FROM app_project
                WHERE id=? AND state='DRAFT' AND environment='SANDBOX'
                FOR UPDATE
                """,r->new Target(r.getObject(1,UUID.class),r.getString(2),
                    r.getString(3)),input.projectId());
            if(project==null || !actor.organizations().contains(project.org()) ||
               ProjectJourney.profile(ProjectGuides.candidate(c,input.projectId()))
                   !=ProjectJourney.VM0038)throw hidden();
            Energy energy=energy(c,input.projectId(),period,input.energyBindingId());
            if(energy==null || !project.org().equals(energy.organization()) ||
               !"CANDIDATE_NOT_ADMITTED".equals(OpenE8InputBindings.state(c,
                    input.projectId(),period,energy.id())))
                throw conflict("Pacote horário de entradas precisa ser o atual e íntegro.");
            UUID latestEnergy=first(c,"""
                SELECT id FROM app_open_e8_input_binding_candidate
                WHERE project_id=? AND period_code=?
                ORDER BY version DESC LIMIT 1
                """,r->r.getObject(1,UUID.class),input.projectId(),period);
            if(!energy.id().equals(latestEnergy))
                throw conflict("Existe uma versão posterior do pacote de entradas.");
            Fleet fleet=fleet(c,input.ratingSnapshotId(),project.org());
            if(fleet==null)throw hidden();
            check(fleet,energy.year(),coverage(c,fleet));
            if(!rightsCurrent(c,fleet,project.org()))
                throw conflict("Licenças ou direitos de processamento EVR/PBEV indisponíveis.");
            Stored latest=first(c,SELECT+"""
                WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
                """,OpenE8FleetSelections::stored,input.projectId(),period);
            PilotCommands.requireVersion(expected,latest==null?0:latest.version());
            if(expected>=100000000)throw conflict("Limite de versões atingido.");
            if(latest!=null && latest.energy().equals(energy.id()) &&
                latest.rating().equals(fleet.ratingId()))
                throw conflict("Estas mesmas versões já estão associadas.");
            String digest=signature(input.projectId(),period,energy,fleet);
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_e8_fleet_selection
                (id,project_id,organization_id,environment,period_code,version,
                 energy_binding_id,fleet_snapshot_id,rating_snapshot_id,
                 observation_year,cycle,fleet_source_sha256,rating_lineage_sha256,
                 selection_sha256,published_by)
                VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,input.projectId(),project.org(),"SANDBOX",period,
                expected+1,energy.id(),fleet.fleetId(),fleet.ratingId(),
                fleet.year(),fleet.cycle(),HexFormat.of().parseHex(fleet.fleetSha()),
                HexFormat.of().parseHex(fleet.ratingSha()),HexFormat.of().parseHex(digest),
                actor.id());
            update(c,"""
                INSERT INTO app_open_e8_fleet_selection_command
                (actor_id,command_key,project_id,selection_id,fingerprint)
                VALUES (?,?,?,?,?)
                """,actor.id(),command,input.projectId(),id,fingerprint);
            ComplianceStaff.audit(c,actor.id(),null,"ASSIGN_OPEN_E8_FLEET_TO_PROJECT");
            return view(c,first(c,SELECT+"""
                WHERE project_id=? AND id=?
                """,OpenE8FleetSelections::stored,input.projectId(),id));
        });
    }

    /** Latest published metadata for the document, never a licensed rating row. */
    static View newest(Connection c, String project) throws SQLException {
        Stored latest = first(c, SELECT + """
            WHERE project_id=?
            ORDER BY published_at DESC, version DESC
            LIMIT 1
            """, OpenE8FleetSelections::stored, project);
        return latest == null ? null : view(c, latest);
    }

    /** Read latest staff-published metadata only, never licensed source rows.
     * Historical selections remain queryable but do not imply E8 admission. */
    static String currentState(Connection c,String project,String period)
        throws SQLException {
        Stored latest=first(c,SELECT+"""
            WHERE project_id=? AND period_code=?
            ORDER BY version DESC LIMIT 1
            """,OpenE8FleetSelections::stored,project,period);
        return latest==null?"FLEET_SELECTION_MISSING":view(c,latest).state();
    }

    private static View view(Connection c,Stored saved)throws SQLException{
        var gates=new ArrayList<String>();
        Energy energy=energy(c,saved.project(),saved.period(),saved.energy());
        if(energy==null || !saved.organization().equals(energy.organization()) ||
           !"CANDIDATE_NOT_ADMITTED".equals(OpenE8InputBindings.state(c,
                saved.project(),saved.period(),saved.energy())))
            gates.add("ENERGY_AND_GRID_BINDING_STALE");
        UUID latestEnergy=first(c,"""
            SELECT id FROM app_open_e8_input_binding_candidate
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),saved.project(),saved.period());
        if(!saved.energy().equals(latestEnergy))gates.add("ENERGY_VERSION_SUPERSEDED");
        if(energy!=null && energy.year()!=saved.year())
            gates.add("PERIOD_YEAR_CHANGED");
        Fleet fleet=fleet(c,saved.rating(),saved.organization());
        if(fleet==null || fleet.year()!=saved.year() ||
           !saved.fleet().equals(fleet.fleetId()) ||
           !saved.fleetSha().equals(fleet.fleetSha()) ||
           !saved.ratingSha().equals(fleet.ratingSha()))
            gates.add("FLEET_RATING_SOURCE_CHANGED");
        else {
            try{
                check(fleet,saved.year(),coverage(c,fleet));
                if(!rightsCurrent(c,fleet,saved.organization()))
                    gates.add("FLEET_RATING_LICENSE_EXPIRED_OR_REVOKED");
            } catch(PilotProblem invalid){
                gates.add("FLEET_RATING_SOURCE_SUSPENDED_OR_STALE");
            }
        }
        UUID latest=first(c,"""
            SELECT id FROM app_open_e8_fleet_selection
            WHERE project_id=? AND period_code=? ORDER BY version DESC LIMIT 1
            """,r->r.getObject(1,UUID.class),saved.project(),saved.period());
        if(!saved.id().equals(latest))gates.add("SELECTION_VERSION_SUPERSEDED");
        boolean stale=!gates.isEmpty();
        gates.add("EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED");
        gates.add("EF_FUEL_IR_AND_DY_NOT_ADMITTED");
        gates.add("BE_PE_ER_EXECUTION_NOT_ENABLED");
        return new View(saved.id(),saved.project(),saved.period(),saved.version(),
            saved.energy(),saved.fleet(),saved.rating(),saved.year(),saved.cycle(),
            saved.fleetSha(),saved.ratingSha(),saved.digest(),
            stale?"STALE_NOT_ADMITTED":"PREPARATORY_NOT_ADMITTED",stale,false,
            saved.created(),List.copyOf(gates));
    }

    static void check(Fleet f,int year,Coverage coverage){
        if(!"STOCK_IN_CIRCULATION".equals(f.stockKind()) ||
            !"BR".equals(f.geography()) ||
            !"PREPARATORY".equals(f.stockState()) ||
            !"PREPARATORY".equals(f.ratingState()) ||
            !"OFFICIAL_TOTAL_NOT_VERIFIED".equals(f.fleetCoverage()) ||
            f.fleetPurged()!=null || f.ratingPurged()!=null ||
            f.ingestionPurged()!=null || year!=f.year() || year!=f.fleetYear() ||
            f.denominator()==null || f.denominator()<=0 ||
            f.fleetModels()==null || f.ratingModels()==null ||
            f.fleetModels()<1 || !f.fleetModels().equals(f.ratingModels()) ||
            coverage.stockRows()!=f.fleetModels() ||
            coverage.ratingRows()!=f.ratingModels() ||
            coverage.stockTotal()!=f.denominator() ||
            coverage.ratingTotal()!=f.denominator() ||
            coverage.mismatched()!=0 || coverage.acceptedPairs()!=f.ratingModels())
            throw conflict("Frota EVR, cobertura de ratings, comparabilidade, licenças ou ano incoerentes.");
    }

    static Coverage coverage(Connection c,Fleet f)throws SQLException{
        Object[] counts=first(c,"""
            SELECT count(*),
                count(l.model_id),
                coalesce(sum(w.observed_stock),0),
                coalesce(sum(l.evr),0),
                count(*) FILTER (WHERE l.model_id IS NULL OR l.evr<>w.observed_stock)
            FROM app_fleet_composition_weight w
            LEFT JOIN app_vehicle_rating_snapshot_line l
              ON l.snapshot_id=? AND l.model_id=w.model_id
            WHERE w.snapshot_id=?
            """,r->new Object[]{r.getLong(1),r.getLong(2),r.getLong(3),
                r.getLong(4),r.getLong(5)},f.ratingId(),f.fleetId());
        Long accepted=first(c,"""
            SELECT count(*) FROM app_vehicle_rating_snapshot_line l
            JOIN app_vehicle_comparable_pair p ON p.id=l.pair_id
            JOIN app_vehicle_rating ev ON ev.id=p.bev_rating_id
            JOIN app_vehicle_rating ff ON ff.id=p.fossil_rating_id
            WHERE l.snapshot_id=? AND p.fleet_snapshot_id=?
              AND p.status='ACCEPTED' AND p.version=2
              AND ev.status='CANDIDATE' AND ff.status='CANDIDATE'
              AND ev.purged_at IS NULL AND ff.purged_at IS NULL
              AND ev.role='BEV' AND ff.role='FOSSIL'
              AND ff.fuel='GASOLINE'
              AND ev.normalized_value IS NOT NULL
              AND ff.normalized_value IS NOT NULL
            """,r->r.getLong(1),f.ratingId(),f.fleetId());
        return new Coverage((long)counts[0],(long)counts[1],(long)counts[2],
            (long)counts[3],(long)counts[4],accepted==null?0:accepted);
    }

    /** Revalidate every referenced original license at use-time.
     * A snapshot status is NOT an authorization; expiration without a
     * formal revocation still blocks project publication and freshness. */
    static boolean rightsCurrent(Connection c,Fleet f,UUID organization)
        throws SQLException {
        Object[] result=first(c,"""
            WITH chosen AS (
              SELECT i.source_license_id AS license_id
              FROM app_fleet_composition_snapshot fs
              JOIN app_fleet_ingestion i ON i.id=fs.ingestion_id
              WHERE fs.id=? AND fs.organization_id=?
              UNION
              SELECT v.source_license_id AS license_id
              FROM app_vehicle_rating_snapshot_line l
              JOIN app_vehicle_comparable_pair p ON p.id=l.pair_id
              JOIN app_vehicle_rating v
                ON v.id IN (p.bev_rating_id,p.fossil_rating_id)
              WHERE l.snapshot_id=? AND l.organization_id=?
            )
            SELECT count(*),count(*) FILTER (WHERE EXISTS (
              SELECT 1 FROM app_content_license lic
              JOIN LATERAL (
                SELECT version,outcome,decided_by,decided_at
                FROM app_content_license_decision
                WHERE license_id=lic.id ORDER BY version DESC LIMIT 1
              ) decision ON true
              WHERE lic.id=chosen.license_id
                AND lic.organization_id=? AND lic.environment='SANDBOX'
                AND lic.purpose='AUTHORIZED_PROCESSING'
                AND lic.project_id IS NULL AND lic.actor_id IS NULL
                AND lic.starts_at<=statement_timestamp()
                AND (lic.ends_at IS NULL OR lic.ends_at>statement_timestamp())
                AND decision.outcome='APPROVED'
                AND decision.decided_at<=statement_timestamp()
                AND lic.proof_sha256 IS NOT NULL
                AND lic.submitted_by<>decision.decided_by
                AND EXISTS (
                  SELECT 1 FROM app_content_license_capability cap
                  WHERE cap.license_id=lic.id
                    AND cap.decision_version=decision.version
                    AND cap.operation='EXTRACT_TEXT'
                )
                AND EXISTS (
                  SELECT 1 FROM app_content_license_capability cap
                  WHERE cap.license_id=lic.id
                    AND cap.decision_version=decision.version
                    AND cap.operation='EXPORT_DERIVATIVES'
                )
            ))
            FROM chosen
            """,r->new Object[]{r.getLong(1),r.getLong(2)},
            f.fleetId(),organization,f.ratingId(),organization,organization);
        return result!=null && (long)result[0]>0 &&
            result[0].equals(result[1]);
    }

    private static Energy energy(Connection c,String project,String period,UUID id)
        throws SQLException{
        return first(c,"""
            SELECT b.id,b.period_code,b.version,b.organization_id,
               min(extract(year from h.hour_start AT TIME ZONE 'UTC'))::int,
               max(extract(year from h.hour_start AT TIME ZONE 'UTC'))::int,
               encode(b.input_sha256,'hex')
            FROM app_open_e8_input_binding_candidate b
            JOIN app_open_e8_input_binding_hour h
              ON h.project_id=b.project_id AND h.binding_id=b.id
            WHERE b.project_id=? AND b.period_code=? AND b.id=?
            GROUP BY b.id
            """,r->{
                int firstYear=r.getInt(5),lastYear=r.getInt(6);
                if(firstYear!=lastYear)throw conflict(
                    "O período cruza dois anos; dividir a referência anual EVR.");
                return new Energy(r.getObject(1,UUID.class),r.getString(2),
                    r.getInt(3),r.getObject(4,UUID.class),firstYear,
                    r.getString(7));
            },project,period,id);
    }
    static Fleet fleet(Connection c,UUID rating,UUID org)throws SQLException{
        return first(c,"""
            SELECT f.id,r.id,r.year,f.observation_year,r.cycle,
                   encode(f.source_sha256,'hex'),
                   encode(r.lineage_digest,'hex'),
                   f.coverage_state,r.status,i.status,i.data_kind,i.geography,
                   f.denominator,f.number_of_models,r.model_count,
                   f.purged_at,r.purged_at,i.purged_at
            FROM app_vehicle_rating_snapshot r
            JOIN app_fleet_composition_snapshot f
              ON f.id=r.fleet_snapshot_id AND f.organization_id=r.organization_id
            JOIN app_fleet_ingestion i
              ON i.id=f.ingestion_id AND i.organization_id=f.organization_id
            WHERE r.id=? AND r.organization_id=? AND f.organization_id=?
            """,r->new Fleet(r.getObject(1,UUID.class),r.getObject(2,UUID.class),
                r.getInt(3),r.getInt(4),r.getString(5),r.getString(6),
                r.getString(7),r.getString(8),r.getString(9),r.getString(10),
                r.getString(11),r.getString(12),
                (Long)r.getObject(13),
                (Integer)r.getObject(14),(Integer)r.getObject(15),
                r.getObject(16,OffsetDateTime.class),
                r.getObject(17,OffsetDateTime.class),
                r.getObject(18,OffsetDateTime.class)),rating,org,org);
    }
    private static String signature(String project,String period,Energy energy,
                                    Fleet fleet){
        String value="CF-OPEN-E8-FLEET-SELECTION-V1|"+project+"|"+period+"|"+
            energy.id()+"|"+energy.sha()+"|"+energy.year()+"|"+
            fleet.fleetId()+"|"+fleet.fleetSha()+"|"+fleet.ratingId()+"|"+
            fleet.ratingSha()+"|"+fleet.year()+"|"+fleet.cycle();
        try{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
            .digest(value.getBytes(StandardCharsets.UTF_8)));}
        catch(NoSuchAlgorithmException e){throw new IllegalStateException(e);}
    }
    private static final String SELECT="""
        SELECT id,project_id,period_code,version,organization_id,
               energy_binding_id,fleet_snapshot_id,rating_snapshot_id,
               observation_year,cycle,encode(fleet_source_sha256,'hex'),
               encode(rating_lineage_sha256,'hex'),
               encode(selection_sha256,'hex'),published_at
        FROM app_open_e8_fleet_selection
        """;
    private static Stored stored(java.sql.ResultSet r)throws SQLException{
        return new Stored(r.getObject(1,UUID.class),r.getString(2),
            r.getString(3),r.getInt(4),r.getObject(5,UUID.class),
            r.getObject(6,UUID.class),r.getObject(7,UUID.class),
            r.getObject(8,UUID.class),r.getInt(9),r.getString(10),
            r.getString(11),r.getString(12),r.getString(13),
            r.getObject(14,OffsetDateTime.class));
    }
    private static PilotProblem hidden(){
        return new PilotProblem(404,"E8_FLEET_SELECTION_NOT_VISIBLE","fleet",RULE,
            "Seleção indisponível.");
    }
    private static PilotProblem conflict(String msg){
        return new PilotProblem(409,"E8_FLEET_SELECTION_CONFLICT","fleet",RULE,msg);
    }
    private static PilotProblem bad(String field,String msg){
        return PilotProblem.invalid("E8_FLEET_SELECTION_INVALID",field,RULE,msg);
    }
}
