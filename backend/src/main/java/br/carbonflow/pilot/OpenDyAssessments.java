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
import org.eclipse.microprofile.config.inject.ConfigProperty;
import java.util.Set;
import java.util.Optional;
import java.util.Arrays;
import java.util.stream.Collectors;

/** #96: assessment history, separate from the parameter pin and execution. */
@ApplicationScoped
class OpenDyAssessments {
    record Input(Integer projectYear, String outcome, String erc, String erf,
                 String programs, String projectReferences, String evidenceLocator, String coverageNote) {}
    record ReviewInput(String assessmentSha256, String outcome, String ruleReference,
                       String evidenceReferences, String reason) {}
    record Review(UUID reviewerId, String outcome, String assessmentSha256, String ruleReference,
                  String evidenceReferences, String reason, OffsetDateTime reviewedAt) {}
    record Option(String projectId, String title) {}
    record Options(boolean canRecord, boolean canReview, List<Option> projects) {}
    record View(UUID id, String projectId, int projectYear, int version, String methodology,
                String edition, String geography, OffsetDateTime priorStart,
                OffsetDateTime priorEndExclusive, String calendarGate, String outcome,
                String erc, String erf, String dyPreview, String programs, String projectReferences,
                String evidenceLocator, String coverageNote, String assessmentSha256,
                OffsetDateTime recordedAt, String state, boolean stale, boolean usableForE8, Review review) {}
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
    private final PlatformSourceReadiness readiness;
    private final Set<UUID> reviewers;

    OpenDyAssessments(PilotDatabase database, PlatformSourceWork managers, DynamicProjectScope scope,
                      PlatformSourceReadiness readiness,
                      @ConfigProperty(name="carbonflow.platform.source-technical.reviewers") Optional<String> roster) {
        this.database=database;
        this.managers=managers;
        this.scope=scope;
        this.readiness=readiness;
        reviewers=roster.map(s->Arrays.stream(s.split(",")).map(String::strip)
            .filter(v->!v.isBlank()).map(UUID::fromString).collect(Collectors.toUnmodifiableSet()))
            .orElse(Set.of());
    }

    Options options(String token) {
        return database.transaction(c->{
            var actor=staff(token,c);
            var organizations=c.createArrayOf("uuid",actor.organizations().toArray(UUID[]::new));
            try {
                var rows=query(c,"""
                    SELECT id,title FROM app_project
                    WHERE organization_id=ANY(?) AND environment='SANDBOX' AND state='DRAFT'
                    ORDER BY title,id
                    """,r->new Option(r.getString(1),r.getString(2)),organizations);
                var projects=new java.util.ArrayList<Option>();
                for (var row:rows) {
                    if (ProjectJourney.profile(ProjectGuides.candidate(c,row.projectId()))==ProjectJourney.VM0038)
                        projects.add(row);
                    if (projects.size()>=80) break;
                }
                return new Options(managers.canManage(actor.id()),reviewers.contains(actor.id()),List.copyOf(projects));
            } finally { organizations.free(); }
        });
    }

    private ComplianceStaff.Staff staff(String token, Connection c) throws SQLException {
        var actor=readiness.requireViewer(c,token);
        if (!managers.canManage(actor.id()) && !reviewers.contains(actor.id())) throw hidden();
        return actor;
    }

    History staffHistory(String token, String project, int year) {
        return database.transaction(c -> {
            var actor=staff(token,c);
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

    Result review(String token,String project,UUID assessment,String key,String match,ReviewInput input) {
        return database.transaction(c->{
            var actor=staff(token,c);
            if (!reviewers.contains(actor.id())) throw hidden();
            requireProject(c,project,actor.organizations(),true);
            Stored row=first(c,SELECT+" WHERE project_id=? AND id=?",OpenDyAssessments::stored,project,assessment);
            if (row==null) throw hidden();
            UUID author=first(c,"SELECT author_id FROM app_open_dy_assessment WHERE id=?",
                r->r.getObject(1,UUID.class),assessment);
            if (actor.id().equals(author)) throw hidden();
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            if (input==null || !row.sha().equals(input.assessmentSha256()) || input.outcome()==null
                    || !Set.of("CHECKED_INTERNAL","NEEDS_INFORMATION","REJECTED").contains(input.outcome()))
                throw invalid("Informe o hash exato da avaliação e um parecer interno válido.");
            String rule, evidence, reason;
            try {
                rule=OpenDyAssessmentRules.text(input.ruleReference(),8,300);
                evidence=OpenDyAssessmentRules.text(input.evidenceReferences(),3,2000);
                reason=OpenDyAssessmentRules.text(input.reason(),15,2500);
            } catch (IllegalArgumentException e) { throw invalid(e.getMessage()); }
            String fingerprint=digest("OPEN-DY-REVIEW@1",project,assessment,row.sha(),expected,
                input.outcome(),rule,evidence,reason);
            first(c,"SELECT pg_advisory_xact_lock(hashtextextended(?,0))",r->true,
                "OPEN-DY-REVIEW:"+actor.id()+":"+command);
            Object[] replay=first(c,"""
                SELECT assessment_id,encode(command_sha256,'hex') FROM app_open_dy_review
                WHERE reviewer_id=? AND command_key=?
                """,r->new Object[]{r.getObject(1,UUID.class),r.getString(2)},actor.id(),command);
            if (replay!=null) {
                if (!assessment.equals(replay[0]) || !fingerprint.equals(replay[1]))
                    throw conflict("Esta chave já identifica outro parecer.");
                return new Result(view(c,row,true),false);
            }
            Integer latest=first(c,"SELECT max(version) FROM app_open_dy_assessment WHERE project_id=? AND project_year=?",
                r->r.getInt(1),project,row.year());
            PilotCommands.requireVersion(expected,latest);
            if (row.version()!=latest || stale(c,row)) throw conflict("Avaliação superada ou desatualizada; registre nova versão.");
            if (readReview(c,row.id(),true)!=null) throw conflict("Já existe parecer para esta versão. Registre nova avaliação.");
            if ("CHECKED_INTERNAL".equals(input.outcome()) && ("INCONCLUSIVE".equals(row.outcome()) || row.preview()==null))
                throw invalid("Pesquisa inconclusiva não pode receber parecer de checagem dos totais.");
            update(c,"""
                INSERT INTO app_open_dy_review
                (assessment_id,reviewer_id,outcome,assessment_sha256,rule_reference,evidence_references,
                 reason,command_key,command_sha256)
                VALUES (?,?,?,?,?,?,?,?,?)
                """,assessment,actor.id(),input.outcome(),HexFormat.of().parseHex(row.sha()),rule,evidence,reason,
                command,HexFormat.of().parseHex(fingerprint));
            return new Result(view(c,row,true),true);
        });
    }

    private static Review readReview(Connection c,UUID id,boolean staff) throws SQLException {
        return first(c,"""
            SELECT reviewer_id,outcome,encode(assessment_sha256,'hex'),rule_reference,
                evidence_references,reason,reviewed_at FROM app_open_dy_review WHERE assessment_id=?
            """,r->new Review(staff?r.getObject(1,UUID.class):null,r.getString(2),r.getString(3),
                staff?r.getString(4):null,staff?r.getString(5):null,staff?r.getString(6):null,
                r.getObject(7,OffsetDateTime.class)),id);
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

    private static boolean stale(Connection c,Stored row) throws SQLException {
        Calendar now=calendar(c,row.project(),row.year());
        return OpenCalculationReadiness.triageRevision(c,row.project())!=row.triage()
            || !Objects.equals(now.gate(),row.gate()) || !same(now.from(),row.from()) || !same(now.until(),row.until());
    }

    private static View view(Connection c,Stored row,boolean staff) throws SQLException {
        boolean stale=stale(c,row);
        Review review=readReview(c,row.id(),staff);
        String state=stale?"STALE_NOT_ADMITTED":review==null?"DECLARED_NOT_ADMITTED":
            review.outcome()+"_NOT_ADMITTED";
        return new View(row.id(),row.project(),row.year(),row.version(),"VM0038","1.1","BR",
            row.from(),row.until(),row.gate(),row.outcome(),row.erc(),row.erf(),row.preview(),
            staff?row.programs():null,staff?row.references():null,staff?row.locator():null,staff?row.note():null,
            row.sha(),row.at(),state,stale,false,review);
    }

    private static Calendar calendar(Connection c,String project,int year) throws SQLException {
        // First-year/pre-start treatment requires a distinct normative decision.
        if (year==1) return new Calendar(null,null,"FIRST_YEAR_REVIEW_REQUIRED");
        var calendar=OpenE8Timing.calendar(c,project,year-1);
        if (calendar.gate()!=null) return new Calendar(null,null,calendar.gate());
        var readiness=OpenApplicability.currentReadiness(c,project);
        if (readiness.stale() || !"OPEN_CANDIDATE".equals(readiness.network())
                || !"OPEN_LDV_DCFC_SIN_BR_CANDIDATE".equals(readiness.category()))
            return new Calendar(null,null,"OPEN_BRANCH_REVIEW_REQUIRED");
        return new Calendar(calendar.window().from(),calendar.window().until(),null);
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
