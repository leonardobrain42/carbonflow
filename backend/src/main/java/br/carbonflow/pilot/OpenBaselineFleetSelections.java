package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * BL-EX-01 (CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE): EX ANTE fleet selection for the PD, with NO monitoring period, NO energy
 * binding and NO MR. It references the platform EVR/rating snapshots (same validators as the monitored V64 handoff, which this
 * class does not touch) and records a declared reference date. Staff-only (MFA + platform source manager), versioned,
 * immutable, never admitted and never an E8 input.
 *
 * Observation-year rule OPEN-BASELINE-FLEET-YEAR@1 (internal, reversible policy D22): the observation year is the calendar year of the
 * declared reference date, the project credit start must be resolved (V88 calendar without gate), and the snapshot year must
 * equal the observation year. The year is never taken from "today" and never converted to a relative project year.
 */
@ApplicationScoped
class OpenBaselineFleetSelections {
    static final String RULE="CF-OPEN-008";
    static final String YEAR_RULE="OPEN-BASELINE-FLEET-YEAR@1";
    record Option(String projectId,String title,UUID organizationId,LocalDate creditStart,String calendarGate,int currentVersion) {}
    record Input(String projectId,String referenceDate,UUID ratingSnapshotId,String rationale) {}
    record View(UUID id,String projectId,int version,String purpose,LocalDate referenceDate,int observationYear,
                LocalDate creditStart,String yearRule,UUID fleetSnapshotId,UUID ratingSnapshotId,String cycle,
                String fleetSourceSha256,String ratingLineageSha256,String selectionSha256,String rationale,
                String state,boolean stale,boolean usableForE8,OffsetDateTime publishedAt,List<String> gates) {}
    private record Stored(UUID id,String project,int version,UUID organization,LocalDate referenceDate,int year,
                          LocalDate creditStart,UUID fleet,UUID rating,String cycle,String fleetSha,String ratingSha,
                          String digest,String rationale,OffsetDateTime created) {}

    private final PilotDatabase database;
    private final PlatformSourceWork managers;

    OpenBaselineFleetSelections(PilotDatabase database,PlatformSourceWork managers) {
        this.database=database;this.managers=managers;
    }

    List<Option> options(String token) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(actor.organizations().isEmpty())return List.of();
            var organizations=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try{
                var projects=query(c,"""
                    SELECT id,title,organization_id FROM app_project
                    WHERE organization_id=ANY(?) AND environment='SANDBOX' AND state='DRAFT'
                    ORDER BY title,id LIMIT 150
                    """,r->new Object[]{r.getString(1),r.getString(2),r.getObject(3,UUID.class)},organizations);
                var result=new ArrayList<Option>();
                for(Object[] row:projects){
                    String project=(String)row[0];
                    if(ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)continue;
                    var calendar=OpenE8Timing.calendar(c,project,1);
                    Integer version=first(c,"SELECT coalesce(max(version),0) FROM app_open_baseline_fleet_selection WHERE project_id=?",
                        r->r.getInt(1),project);
                    result.add(new Option(project,(String)row[1],(UUID)row[2],calendar.creditStart(),calendar.gate(),version));
                }
                ComplianceStaff.audit(c,actor.id(),null,"VIEW_OPEN_BASELINE_FLEET_OPTIONS");
                return List.copyOf(result);
            }finally{organizations.free();}
        });
    }

    List<View> list(String token,String project) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            UUID org=first(c,"""
                SELECT organization_id FROM app_project WHERE id=? AND environment='SANDBOX' AND state='DRAFT'
                """,r->r.getObject(1,UUID.class),project);
            if(org==null || !actor.organizations().contains(org))throw hidden();
            var stored=query(c,SELECT+" WHERE project_id=? ORDER BY version DESC LIMIT 20",
                OpenBaselineFleetSelections::stored,project);
            var out=new ArrayList<View>();
            for(var record:stored)out.add(view(c,record));
            ComplianceStaff.audit(c,actor.id(),null,"VIEW_OPEN_BASELINE_FLEET_SELECTIONS");
            return List.copyOf(out);
        });
    }

    View assign(String token,String key,String match,Input input) {
        return database.transaction(c->{
            var actor=managers.requireManager(c,token);
            if(input==null || input.projectId()==null || input.ratingSnapshotId()==null || input.referenceDate()==null)
                throw bad("input","Informe projeto, data de referência, snapshot de ratings e justificativa.");
            LocalDate reference;
            String rationale;
            try{
                reference=LocalDate.parse(input.referenceDate());
                rationale=OpenDyAssessmentRules.text(input.rationale(),15,1000);
            }catch(DateTimeParseException|IllegalArgumentException invalid){
                throw bad("input","Informe a data de referência (AAAA-MM-DD) e uma justificativa de 15 a 1000 caracteres.");
            }
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            byte[] fingerprint=AccessSecrets.digest("OPEN-BASELINE-FLEET-HANDOFF-V1|"+input.projectId()+"|"+expected+"|"
                +reference+"|"+input.ratingSnapshotId()+"|"+rationale);
            Object[] replay=first(c,"""
                SELECT project_id,selection_id,fingerprint FROM app_open_baseline_fleet_selection_command
                WHERE actor_id=? AND command_key=?
                """,r->new Object[]{r.getString(1),r.getObject(2,UUID.class),r.getBytes(3)},actor.id(),command);
            if(replay!=null){
                if(!input.projectId().equals(replay[0]) || !MessageDigest.isEqual(fingerprint,(byte[])replay[2]))
                    throw conflict("Chave de idempotência foi reutilizada com outra seleção.");
                Stored previous=first(c,SELECT+" WHERE project_id=? AND id=?",OpenBaselineFleetSelections::stored,
                    input.projectId(),(UUID)replay[1]);
                if(previous==null)throw hidden();
                return view(c,previous);
            }
            UUID org=first(c,"""
                SELECT organization_id FROM app_project
                WHERE id=? AND state='DRAFT' AND environment='SANDBOX' FOR UPDATE
                """,r->r.getObject(1,UUID.class),input.projectId());
            if(org==null || !actor.organizations().contains(org) ||
               ProjectJourney.profile(ProjectGuides.candidate(c,input.projectId()))!=ProjectJourney.VM0038)throw hidden();
            var calendar=OpenE8Timing.calendar(c,input.projectId(),1);
            if(calendar.gate()!=null || calendar.creditStart()==null)
                throw conflict("O início de creditação do projeto ainda não está resolvido ("+calendar.gate()+"). Resolva o calendário antes de selecionar a frota.");
            int year=reference.getYear();
            var fleet=OpenE8FleetSelections.fleet(c,input.ratingSnapshotId(),org);
            if(fleet==null)throw hidden();
            OpenE8FleetSelections.check(fleet,year,OpenE8FleetSelections.coverage(c,fleet));
            if(!OpenE8FleetSelections.rightsCurrent(c,fleet,org))
                throw conflict("Licenças ou direitos de processamento EVR/PBEV indisponíveis.");
            Stored latest=first(c,SELECT+" WHERE project_id=? ORDER BY version DESC LIMIT 1",
                OpenBaselineFleetSelections::stored,input.projectId());
            PilotCommands.requireVersion(expected,latest==null?0:latest.version());
            if(expected>=100000000)throw conflict("Limite de versões atingido.");
            if(latest!=null && latest.rating().equals(fleet.ratingId()) && latest.referenceDate().equals(reference)
               && latest.creditStart().equals(calendar.creditStart()))
                throw conflict("Esta mesma referência já está selecionada.");
            String digest=signature(input.projectId(),reference,calendar.creditStart(),fleet);
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_baseline_fleet_selection
                (id,project_id,organization_id,environment,version,reference_date,observation_year,credit_start,year_rule,
                 fleet_snapshot_id,rating_snapshot_id,cycle,fleet_source_sha256,rating_lineage_sha256,selection_sha256,
                 rationale,published_by)
                VALUES (?,?,?,'SANDBOX',?,?::date,?,?::date,?,?,?,?,?,?,?,?,?)
                """,id,input.projectId(),org,expected+1,reference.toString(),year,calendar.creditStart().toString(),YEAR_RULE,
                fleet.fleetId(),fleet.ratingId(),fleet.cycle(),HexFormat.of().parseHex(fleet.fleetSha()),
                HexFormat.of().parseHex(fleet.ratingSha()),HexFormat.of().parseHex(digest),rationale,actor.id());
            update(c,"""
                INSERT INTO app_open_baseline_fleet_selection_command(actor_id,command_key,project_id,selection_id,fingerprint)
                VALUES (?,?,?,?,?)
                """,actor.id(),command,input.projectId(),id,fingerprint);
            ComplianceStaff.audit(c,actor.id(),null,"ASSIGN_OPEN_BASELINE_FLEET_EX_ANTE");
            return view(c,first(c,SELECT+" WHERE project_id=? AND id=?",OpenBaselineFleetSelections::stored,input.projectId(),id));
        });
    }

    /** Derived status is recomputed from the owners at read time; a stored row is never edited. */
    private static View view(Connection c,Stored saved)throws SQLException{
        var gates=new ArrayList<String>();
        var calendar=OpenE8Timing.calendar(c,saved.project(),1);
        if(calendar.gate()!=null || !saved.creditStart().equals(calendar.creditStart()))gates.add("CREDIT_START_CHANGED_OR_UNRESOLVED");
        var fleet=OpenE8FleetSelections.fleet(c,saved.rating(),saved.organization());
        if(fleet==null || fleet.year()!=saved.year() || !saved.fleet().equals(fleet.fleetId())
           || !saved.fleetSha().equals(fleet.fleetSha()) || !saved.ratingSha().equals(fleet.ratingSha()))
            gates.add("FLEET_RATING_SOURCE_CHANGED");
        else {
            try{
                OpenE8FleetSelections.check(fleet,saved.year(),OpenE8FleetSelections.coverage(c,fleet));
                if(!OpenE8FleetSelections.rightsCurrent(c,fleet,saved.organization()))
                    gates.add("FLEET_RATING_LICENSE_EXPIRED_OR_REVOKED");
            }catch(PilotProblem invalid){
                gates.add("FLEET_RATING_SOURCE_SUSPENDED_OR_STALE");
            }
        }
        UUID latest=first(c,"SELECT id FROM app_open_baseline_fleet_selection WHERE project_id=? ORDER BY version DESC LIMIT 1",
            r->r.getObject(1,UUID.class),saved.project());
        if(!saved.id().equals(latest))gates.add("SELECTION_VERSION_SUPERSEDED");
        boolean stale=!gates.isEmpty();
        gates.add("EVR_AND_RATINGS_NOT_NORMATIVELY_ADMITTED");
        gates.add("EX_ANTE_REFERENCE_IS_NOT_AN_E8_INPUT");
        return new View(saved.id(),saved.project(),saved.version(),"PD_EX_ANTE",saved.referenceDate(),saved.year(),
            saved.creditStart(),YEAR_RULE,saved.fleet(),saved.rating(),saved.cycle(),saved.fleetSha(),saved.ratingSha(),
            saved.digest(),saved.rationale(),stale?"STALE_NOT_ADMITTED":"PREPARATORY_NOT_ADMITTED",stale,false,
            saved.created(),List.copyOf(gates));
    }

    private static String signature(String project,LocalDate reference,LocalDate creditStart,OpenE8FleetSelections.Fleet fleet){
        String value="CF-OPEN-BASELINE-FLEET-SELECTION-V1|"+project+"|"+reference+"|"+creditStart+"|"+YEAR_RULE+"|"
            +fleet.fleetId()+"|"+fleet.fleetSha()+"|"+fleet.ratingId()+"|"+fleet.ratingSha()+"|"+fleet.year()+"|"+fleet.cycle();
        return HexFormat.of().formatHex(AccessSecrets.digest(value));
    }

    private static final String SELECT="""
        SELECT id,project_id,version,organization_id,reference_date,observation_year,credit_start,fleet_snapshot_id,
               rating_snapshot_id,cycle,encode(fleet_source_sha256,'hex'),encode(rating_lineage_sha256,'hex'),
               encode(selection_sha256,'hex'),rationale,published_at
        FROM app_open_baseline_fleet_selection
        """;
    private static Stored stored(java.sql.ResultSet r)throws SQLException{
        return new Stored(r.getObject(1,UUID.class),r.getString(2),r.getInt(3),r.getObject(4,UUID.class),
            r.getObject(5,LocalDate.class),r.getInt(6),r.getObject(7,LocalDate.class),r.getObject(8,UUID.class),
            r.getObject(9,UUID.class),r.getString(10),r.getString(11),r.getString(12),r.getString(13),r.getString(14),
            r.getObject(15,OffsetDateTime.class));
    }
    private static PilotProblem hidden(){
        return new PilotProblem(404,"BASELINE_FLEET_SELECTION_NOT_VISIBLE","fleet",RULE,"Seleção indisponível.");
    }
    private static PilotProblem conflict(String msg){
        return new PilotProblem(409,"BASELINE_FLEET_SELECTION_CONFLICT","fleet",RULE,msg);
    }
    private static PilotProblem bad(String field,String msg){
        return PilotProblem.invalid("BASELINE_FLEET_SELECTION_INVALID",field,RULE,msg);
    }
}
