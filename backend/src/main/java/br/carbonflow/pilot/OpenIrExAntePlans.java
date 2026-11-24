package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.ResultSet;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.HexFormat;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/** Planning only; no dependency on measurements, period, fleet handoff or parameter pins. */
@ApplicationScoped
class OpenIrExAntePlans {
    static final String POLICY="OPEN-IR-EX-ANTE@1";
    record ParameterPlan(String source,String responsible,String procedure) {
        ParameterPlan validated() {
            return new ParameterPlan(text(source,3,500),text(responsible,3,500),text(procedure,15,2000));
        }
    }
    record Input(ParameterPlan evr,ParameterPlan afec,ParameterPlan mpg,ParameterPlan fuel,String rationale) {
        Input validated() {
            if(evr==null||afec==null||mpg==null||fuel==null)
                throw invalid("Informe os quatro planos de parâmetros.");
            return new Input(evr.validated(),afec.validated(),mpg.validated(),fuel.validated(),text(rationale,15,2500));
        }
    }
    record Context(int triageVersion,int intakeVersion,int applicabilityVersion,String state,String sha256) {}
    record Revision(UUID id,int version,Input plan,String planSha256,String contextSha256,
                    String state,boolean stale,OffsetDateTime recordedAt,boolean usableForE8) {}
    record View(String projectId,Context context,int latestVersion,boolean editable,List<Revision> revisions) {}
    record Result(Revision revision,boolean created) {}
    record ReviewTarget(UUID id,int version,String sha256,UUID author,Input plan) {}

    /** Called only inside an authorized staff transaction with the project lock. */
    static ReviewTarget currentReviewTarget(Connection c,String project) throws SQLException {
        Context context=context(c,project);
        if(!"OPEN_CANDIDATE".equals(context.state()))return null;
        return first(c,"""
            SELECT id,version,encode(plan_sha256,'hex'),author_id,
                plan #>> '{evr,source}',plan #>> '{evr,responsible}',plan #>> '{evr,procedure}',
                plan #>> '{afec,source}',plan #>> '{afec,responsible}',plan #>> '{afec,procedure}',
                plan #>> '{mpg,source}',plan #>> '{mpg,responsible}',plan #>> '{mpg,procedure}',
                plan #>> '{fuel,source}',plan #>> '{fuel,responsible}',plan #>> '{fuel,procedure}',
                plan->>'rationale'
            FROM app_open_ir_ex_ante_plan WHERE project_id=? AND context_sha256=?
              AND version=(SELECT max(version) FROM app_open_ir_ex_ante_plan WHERE project_id=?)
            """,r->new ReviewTarget(r.getObject(1,UUID.class),r.getInt(2),r.getString(3),
                r.getObject(4,UUID.class),new Input(metric(r,5),metric(r,8),metric(r,11),metric(r,14),r.getString(17))),
                project,bytes(context.sha256()),project);
    }
    private static ParameterPlan metric(ResultSet r,int i)throws SQLException {
        return new ParameterPlan(r.getString(i),r.getString(i+1),r.getString(i+2));
    }
    private record Stored(UUID id,String project,int version,String json,String sha,String contextSha,
                          OffsetDateTime recordedAt,String commandSha) {}

    private final DynamicProjectScope scope;
    private final ObjectMapper mapper;
    OpenIrExAntePlans(DynamicProjectScope scope,ObjectMapper mapper) {
        this.scope=scope;this.mapper=mapper;
    }

    View read(String token,String project) {
        return scope.read(token,project,"VIEW_GUIDES",(c,ctx)->{
            requireBaseline(c,ctx,false);
            Context context=context(c,project);
            int latest=latest(c,project);
            var rows=query(c,SELECT+" WHERE project_id=? ORDER BY version DESC LIMIT 50",
                OpenIrExAntePlans::stored,project);
            return new View(project,context,latest,allowed(context)&&canEdit(ctx),
                rows.stream().map(r->view(r,context,latest)).toList());
        });
    }

    Result save(String token,String project,String key,String match,Input raw) {
        return scope.write(token,project,"SAVE_GUIDE",(c,ctx)->{
            requireBaseline(c,ctx,true);
            String command=PilotCommands.key(key);
            int expected=PilotCommands.expected(match);
            if(raw==null)throw invalid("Informe o planejamento.");
            Input input=raw.validated();
            String json=encode(input);
            String commandSha=OpenIrAssessments.digest(POLICY,"COMMAND",project,expected,json);
            Stored replay=first(c,SELECT+" WHERE author_id=? AND command_key=?",
                OpenIrExAntePlans::stored,ctx.actor().id(),command);
            Context current=context(c,project);
            int latest=latest(c,project);
            if(replay!=null) {
                if(!project.equals(replay.project())||!commandSha.equals(replay.commandSha()))
                    throw conflict("Chave já utilizada para outro planejamento.");
                return new Result(view(replay,current,latest),false);
            }
            if(!allowed(current))throw conflict("O recorte atual não permite este planejamento OPEN.");
            PilotCommands.requireVersion(expected,latest);
            if(latest>=100000000)throw conflict("Limite de versões atingido.");
            String sha=OpenIrAssessments.digest(POLICY,project,expected+1,current.sha256(),ctx.actor().id(),json);
            UUID id=UUID.randomUUID();
            update(c,"""
                INSERT INTO app_open_ir_ex_ante_plan
                  (id,project_id,organization_id,environment,version,triage_version,intake_version,
                   applicability_version,context_sha256,plan,plan_sha256,policy,author_id,command_key,command_sha256)
                VALUES (?,?,?,?,?,?,?,?,?,?::jsonb,?,?,?,?,?)
                """,id,project,ctx.organization(),ctx.environment(),expected+1,current.triageVersion(),
                current.intakeVersion(),current.applicabilityVersion(),bytes(current.sha256()),json,bytes(sha),
                POLICY,ctx.actor().id(),command,bytes(commandSha));
            Stored row=first(c,SELECT+" WHERE id=?",OpenIrExAntePlans::stored,id);
            return new Result(view(row,current,expected+1),true);
        });
    }

    private static boolean canEdit(DynamicProjectScope.Context ctx) {
        return ctx.owner()||ctx.actions().contains("SAVE_GUIDE");
    }
    private static void requireBaseline(Connection c,DynamicProjectScope.Context ctx,boolean write) throws SQLException {
        String activity=first(c,"SELECT activity FROM app_technical_guide WHERE id='baseline'",r->r.getString(1));
        if(!ctx.owner()&&(!ctx.actions().contains("GUIDE_"+activity)||(write&&!canEdit(ctx))))
            throw new PilotProblem(404,"RECURSO_NAO_VISIVEL","project","CF-OPEN-007","Recurso indisponível.");
    }

    static Context context(Connection c,String project) throws SQLException {
        int triage=OpenCalculationReadiness.triageRevision(c,project);
        int intake=first(c,"SELECT coalesce(max(version),0) FROM app_project_intake_draft_revision WHERE project_id=?",r->r.getInt(1),project);
        int applicability=first(c,"SELECT coalesce(max(version),0) FROM app_project_open_applicability_revision WHERE project_id=?",r->r.getInt(1),project);
        String country=first(c,"""
            SELECT payload->>'country' FROM app_project_intake_draft_revision WHERE project_id=?
            ORDER BY version DESC LIMIT 1
            """,r->r.getString(1),project);
        String vehicle=first(c,"""
            SELECT answers #>> '{3,vehicleClass}' FROM app_project_triage_revision WHERE project_id=?
            ORDER BY version DESC LIMIT 1
            """,r->r.getString(1),project);
        var ready=OpenApplicability.currentReadiness(c,project);
        boolean vm=ProjectJourney.profile(ProjectGuides.candidate(c,project))==ProjectJourney.VM0038;
        String state=classify(vm,country,vehicle,ready.category(),ready.network(),ready.stale());
        return new Context(triage,intake,applicability,state,OpenIrAssessments.digest(POLICY,
            project,triage,intake,applicability,vm,country,vehicle,state));
    }

    static String classify(boolean vm,String country,String vehicle,String category,String network,boolean stale) {
        if(!vm)return "METHOD_OUTSIDE_SCOPE";
        if((country!=null&&!country.isBlank()&&!"BR".equals(country))||
           (vehicle!=null&&!vehicle.isBlank()&&!"LDV".equals(vehicle))||
           "OUTSIDE_FIRST_IMPLEMENTED_SCOPE".equals(category)||"CLOSED_RESTRICTION".equals(network))
            return "BRANCH_OUTSIDE_SCOPE";
        if(stale||!"BR".equals(country)||!"LDV".equals(vehicle)||
           !"OPEN_CANDIDATE".equals(network)||!"OPEN_LDV_DCFC_SIN_BR_CANDIDATE".equals(category))
            return "SCOPE_PENDING";
        return "OPEN_CANDIDATE";
    }
    private static boolean allowed(Context context) {
        return "SCOPE_PENDING".equals(context.state())||"OPEN_CANDIDATE".equals(context.state());
    }
    private static int latest(Connection c,String project) throws SQLException {
        return first(c,"SELECT coalesce(max(version),0) FROM app_open_ir_ex_ante_plan WHERE project_id=?",r->r.getInt(1),project);
    }
    private Revision view(Stored row,Context current,int latest) {
        boolean stale=row.version()!=latest||!row.contextSha().equals(current.sha256())||!allowed(current);
        return new Revision(row.id(),row.version(),decode(row.json()),row.sha(),row.contextSha(),
            stale?"STALE_PLAN":"PLANNED_NOT_REVIEWED",stale,row.recordedAt(),false);
    }
    private String encode(Input input) {
        try{return mapper.writeValueAsString(input);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private Input decode(String json) {
        try{return mapper.readValue(json,Input.class);}catch(java.io.IOException e){throw new IllegalStateException(e);}
    }
    private static String text(String value,int min,int max) {
        if(value==null||value.strip().length()<min||value.strip().length()>max||value.indexOf('\0')>=0)
            throw invalid("Preencha fonte, responsável, procedimento e justificativa dentro dos limites.");
        return value.strip();
    }
    private static byte[] bytes(String sha) {return HexFormat.of().parseHex(sha);}
    private static PilotProblem invalid(String message) {return PilotProblem.invalid("IR_PLAN_INVALID","plan","CF-OPEN-007",message);}
    private static PilotProblem conflict(String message) {return new PilotProblem(409,"IR_PLAN_CONFLICT","plan","CF-OPEN-007",message);}
    private static final String SELECT="""
        SELECT id,project_id,version,plan::text,encode(plan_sha256,'hex'),encode(context_sha256,'hex'),
            recorded_at,encode(command_sha256,'hex') FROM app_open_ir_ex_ante_plan
        """;
    private static Stored stored(ResultSet r)throws SQLException {
        return new Stored(r.getObject(1,UUID.class),r.getString(2),r.getInt(3),r.getString(4),r.getString(5),
            r.getString(6),r.getObject(7,OffsetDateTime.class),r.getString(8));
    }
}
