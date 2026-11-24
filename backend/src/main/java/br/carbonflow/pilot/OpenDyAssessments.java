package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/** #96: assessment history, separate from the parameter pin and execution. */
@ApplicationScoped
class OpenDyAssessments {
    record Input(Integer projectYear, String outcome, String erc, String erf,
                 String programs, String projectReferences, String evidenceLocator, String coverageNote) {}
    record View(UUID id, String projectId, int projectYear, int version, String methodology,
                String edition, String geography, OffsetDateTime priorStart,
                OffsetDateTime priorEndExclusive, String calendarGate, String outcome,
                String erc, String erf, String dyPreview, String programs, String projectReferences,
                String evidenceLocator, String coverageNote, String assessmentSha256,
                OffsetDateTime recordedAt, String state, boolean stale, boolean usableForE8) {}
    record Result(View view, boolean created) {}
    record History(String projectId, int projectYear, int latestVersion, OffsetDateTime priorStart,
                   OffsetDateTime priorEndExclusive, String calendarGate, List<View> assessments) {}

    private record Calendar(OffsetDateTime from, OffsetDateTime until, String gate) {}
    private record Stored(UUID id, String project, int year, int version, int triage,
                          OffsetDateTime from, OffsetDateTime until, String gate, String outcome,
                          String erc, String erf, String preview, String programs, String references,
                          String locator, String note, String sha, OffsetDateTime at, String commandSha) {}
    private final PilotDatabase database;
    private final PlatformSourceWork managers;
    private final DynamicProjectScope scope;

    OpenDyAssessments(PilotDatabase database, PlatformSourceWork managers, DynamicProjectScope scope) {
        this.database=database;
        this.managers=managers;
        this.scope=scope;
    }

    History staffHistory(String token, String project, int year) {
        return database.transaction(c -> {
            var actor=managers.requireManager(c,token);
            requireProject(c,project,actor.organizations(),false);
            return history(c,project,year,true);
        });
    }

    History projectHistory(String token, String project, int year) {
        return scope.read(token,project,"VIEW_OPEN_PARAMETER_BINDING",(c,ctx)->{
            if (ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
                throw hidden();
            return history(c,project,year,false);
        });
    }

    Result record(String token, String project, String key, String match, Input input) {
        return database.transaction(c -> {
            var actor=managers.requireManager(c,token);
            UUID organization=requireProject(c,project,actor.organizations(),true);
            if (input==null || input.projectYear()==null) throw invalid("Informe o ano do projeto.");
            int year=input.projectYear();
            checkYear(year);
            int expected=PilotCommands.expected(match);
            String command=PilotCommands.key(key);
            // Serialize replay across different project IDs for the same staff/key.
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,
                "OPEN-DY:"+actor.id()+":"+command);
            String programs, refs, locator, note;
            OpenDyAssessmentRules.Values values;
            try {
                programs=OpenDyAssessmentRules.text(input.programs(),3,1000);
                refs=OpenDyAssessmentRules.text(input.projectReferences(),3,2000);
                locator=OpenDyAssessmentRules.text(input.evidenceLocator(),3,1000);
                note=OpenDyAssessmentRules.text(input.coverageNote(),8,2000);
                // Replay identity does not depend on the *current* calendar.
                values=OpenDyAssessmentRules.values(input.outcome(),input.erc(),input.erf(),null);
            } catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
            String erc=OpenDyAssessmentRules.number(values.erc());
            String erf=OpenDyAssessmentRules.number(values.erf());
            String fingerprint=digest("OPEN-DY-COMMAND@1",project,year,expected,values.outcome(),
                erc,erf,programs,refs,locator,note);
            Stored replay=first(c,SELECT+" WHERE author_id=? AND command_key=?",
                OpenDyAssessments::stored,actor.id(),command);
            if (replay!=null) {
                if (!project.equals(replay.project()) || !fingerprint.equals(replay.commandSha()))
                    throw conflict("Esta chave já identifica outra avaliação.");
                return new Result(view(c,replay,true),false);
            }
            Calendar calendar=calendar(c,project,year);
            if (calendar.gate()!=null && !"INCONCLUSIVE".equals(values.outcome()))
                throw invalid("O ano anterior não está resolvido. Registre pesquisa inconclusiva.");
            Integer current=first(c,"""
                SELECT max(version) FROM app_open_dy_assessment WHERE project_id=? AND project_year=?
                """,r->r.getObject(1,Integer.class),project,year);
            PilotCommands.requireVersion(expected,current==null?0:current);
            if (expected>=100000000) throw conflict("Limite de versões atingido.");
            int triage=OpenCalculationReadiness.triageRevision(c,project);
            UUID id=UUID.randomUUID();
            String hash=digest("OPEN-DY-ASSESSMENT@1",fingerprint,triage,calendar.gate(),
                calendar.from()==null?null:calendar.from().toInstant(),
                calendar.until()==null?null:calendar.until().toInstant(),
                OpenDyAssessmentRules.number(values.preview()));
            update(c,"""
                INSERT INTO app_open_dy_assessment
                (id,project_id,organization_id,environment,project_year,version,triage_version,
                 prior_start,prior_end_exclusive,calendar_gate,outcome,erc,erf,dy_preview,
                 programs,project_references,evidence_locator,coverage_note,assessment_sha256,
                 author_id,command_key,command_sha256)
                VALUES (?,?,?,'SANDBOX',?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?,?)
                """,id,project,organization,year,expected+1,triage,calendar.from(),calendar.until(),
                calendar.gate(),values.outcome(),values.erc(),values.erf(),values.preview(),
                programs,refs,locator,note,HexFormat.of().parseHex(hash),actor.id(),command,
                HexFormat.of().parseHex(fingerprint));
            return new Result(view(c,first(c,SELECT+" WHERE id=?",OpenDyAssessments::stored,id),true),true);
        });
    }

    private static UUID requireProject(Connection c,String project,java.util.Set<UUID> organizations,
                                       boolean lock) throws SQLException {
        UUID organization=first(c,"""
            SELECT organization_id FROM app_project
            WHERE id=? AND environment='SANDBOX' AND state='DRAFT'
            """+(lock?" FOR UPDATE":" FOR SHARE"),r->r.getObject(1,UUID.class),project);
        if (organization==null || !organizations.contains(organization)
                || ProjectJourney.profile(ProjectGuides.candidate(c,project))!=ProjectJourney.VM0038)
            throw hidden();
        return organization;
    }

    private static History history(Connection c,String project,int year,boolean staff) throws SQLException {
        checkYear(year);
        Calendar calendar=calendar(c,project,year);
        var rows=query(c,SELECT+" WHERE project_id=? AND project_year=? ORDER BY version DESC LIMIT 50",
            OpenDyAssessments::stored,project,year);
        var views=new java.util.ArrayList<View>();
        for (var row:rows) views.add(view(c,row,staff));
        return new History(project,year,rows.isEmpty()?0:rows.get(0).version(),
            calendar.from(),calendar.until(),calendar.gate(),List.copyOf(views));
    }

    private static View view(Connection c,Stored row,boolean staff) throws SQLException {
        Calendar now=calendar(c,row.project(),row.year());
        boolean stale=OpenCalculationReadiness.triageRevision(c,row.project())!=row.triage()
            || !Objects.equals(now.gate(),row.gate()) || !same(now.from(),row.from()) || !same(now.until(),row.until());
        return new View(row.id(),row.project(),row.year(),row.version(),"VM0038","1.1","BR",
            row.from(),row.until(),row.gate(),row.outcome(),row.erc(),row.erf(),row.preview(),
            staff?row.programs():null,staff?row.references():null,staff?row.locator():null,staff?row.note():null,
            row.sha(),row.at(),stale?"STALE_NOT_ADMITTED":"DECLARED_NOT_ADMITTED",stale,false);
    }

    private static Calendar calendar(Connection c,String project,int year) throws SQLException {
        // First-year/pre-start treatment requires a distinct normative decision.
        if (year==1) return new Calendar(null,null,"FIRST_YEAR_REVIEW_REQUIRED");
        var calendar=OpenE8Timing.calendar(c,project,year-1);
        return calendar.gate()!=null?new Calendar(null,null,calendar.gate()):
            new Calendar(calendar.window().from(),calendar.window().until(),null);
    }

    private static boolean same(OffsetDateTime a,OffsetDateTime b) {
        return a==null?b==null:b!=null && a.isEqual(b);
    }

    private static String digest(Object... parts) {
        var s=new StringBuilder();
        for (Object part:parts) {
            if (part==null) s.append("-1:");
            else {
                String value=part.toString();
                s.append(value.length()).append(':').append(value);
            }
        }
        return HexFormat.of().formatHex(AccessSecrets.digest(s.toString()));
    }

    private static final String SELECT="""
        SELECT id,project_id,project_year,version,triage_version,prior_start,prior_end_exclusive,
            calendar_gate,outcome,erc,erf,dy_preview,programs,project_references,evidence_locator,
            coverage_note,encode(assessment_sha256,'hex'),recorded_at,encode(command_sha256,'hex')
        FROM app_open_dy_assessment
        """;

    private static Stored stored(ResultSet r) throws SQLException {
        return new Stored(r.getObject(1,UUID.class),r.getString(2),r.getInt(3),r.getInt(4),r.getInt(5),
            r.getObject(6,OffsetDateTime.class),r.getObject(7,OffsetDateTime.class),r.getString(8),r.getString(9),
            OpenDyAssessmentRules.number(r.getBigDecimal(10)),OpenDyAssessmentRules.number(r.getBigDecimal(11)),
            OpenDyAssessmentRules.number(r.getBigDecimal(12)),r.getString(13),r.getString(14),r.getString(15),
            r.getString(16),r.getString(17),r.getObject(18,OffsetDateTime.class),r.getString(19));
    }
    private static void checkYear(int year) {
        if (year<1 || year>100) throw invalid("Ano do projeto entre 1 e 100.");
    }
    private static PilotProblem invalid(String detail) {
        return PilotProblem.invalid("DY_ASSESSMENT_INVALID","assessment","CF-OPEN-007",detail);
    }
    private static PilotProblem hidden() {
        return new PilotProblem(404,"DY_ASSESSMENT_NOT_VISIBLE","project","CF-OPEN-007","Recurso indisponível.");
    }
    private static PilotProblem conflict(String detail) {
        return new PilotProblem(409,"DY_ASSESSMENT_CONFLICT","assessment","CF-OPEN-007",detail);
    }
}
