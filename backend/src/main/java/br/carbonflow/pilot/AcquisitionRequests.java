package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

@ApplicationScoped
class AcquisitionRequests {
    enum Status { OPEN, UNKNOWN, DECLINED, PARTIAL }
    enum Kind { TECHNICAL_INFORMATION, DOCUMENT_REFERENCE, MEASUREMENT }
    enum Nature { PLANNED, ESTIMATED, MEASURED }
    record Criteria(String unit, String period, String boundary, String origin, String disclosure) {}
    record Setup(Kind kind, String purpose, String candidateSource, LocalDate due, UUID assignee, Criteria criteria) {}
    record Reply(Status status, Nature nature, String note) {}
    record Plan(Setup setup, String label, String reason, int guideVersion, String packId,
                String requirementCode, List<String> dependencies) {}
    record Request(UUID id, String section, RequirementResolver.Act act, String field, String taskId, UUID creator) {}
    record Revision(int number, UUID actor, UUID assignee, Status status, Plan plan, Reply reply, OffsetDateTime recordedAt) {}
    record Item(Request request, Revision revision, boolean canPlan, boolean canReply) {}
    record Recipient(UUID id, String name) {}
    record View(List<SectionReadiness.InputNeed> available, List<Item> requests, List<Recipient> recipients, boolean canCreate) {}

    private final DynamicProjectScope scopes;
    private final SectionReadiness readiness;
    private final ObjectMapper json;

    AcquisitionRequests(DynamicProjectScope scopes, SectionReadiness readiness, ObjectMapper json) {
        this.scopes = scopes;
        this.readiness = readiness;
        this.json = json;
    }

    View view(String token, String project, String section, RequirementResolver.Act act) {
        return scopes.read(token,project,"VIEW_GUIDES",(connection,scope) -> {
            var sectionView = readiness.view(connection,scope,section,act);
            boolean canCreate = scope.actions().contains("SAVE_GUIDE");
            var existing = query(connection, """
                    SELECT id,section,act,field,task_id,creator_id FROM app_acquisition_request
                    WHERE project_id=? AND section=? AND act=? ORDER BY field
                    """, AcquisitionRequests::request,project,section,act.name());
            var items = new java.util.ArrayList<Item>();
            for (Request request : existing) {
                var current = latest(connection,request.id());
                if (visible(scope,request,current)) items.add(item(scope,request,current));
            }
            return new View(canCreate ? sectionView.acquisitionPlan() : List.of(),List.copyOf(items),
                    scope.owner() ? recipients(connection,scope,section) : List.of(),canCreate);
        });
    }

    Item plan(String token, String project, String section, RequirementResolver.Act act, String field,
              String etag, Setup data) {
        int expected = PilotCommands.expected(etag);
        return scopes.write(token,project,"SAVE_GUIDE",(connection,scope) -> {
            var view = readiness.view(connection,scope,section,act);
            var request = first(connection, """
                    SELECT id,section,act,field,task_id,creator_id FROM app_acquisition_request
                    WHERE project_id=? AND section=? AND act=? AND field=?
                    """, AcquisitionRequests::request,project,section,act.name(),field);
            var current = request == null ? null : latest(connection,request.id());
            if (request != null && (!visible(scope,request,current) || !canPlan(scope,request))) throw invisible();
            var setup = normalize(data);
            if (setup.assignee() != null && (!scope.owner()
                    || !eligible(connection,scope,section,setup.assignee()))) {
                throw invalid("assignee","Escolha um preparador com acesso vigente ao guia.");
            }
            if (isRetry(scope,current,expected,Status.OPEN) && current.plan().setup().equals(setup)) {
                return item(scope,request,current);
            }
            PilotCommands.requireVersion(expected,current == null ? 0 : current.number());

            Plan plan;
            if (current == null) {
                var need = view.acquisitionPlan().stream().filter(gap -> field.equals(gap.field())).findFirst()
                        .orElseThrow(() -> invalid("field","Escolha uma lacuna da revisão atual do guia."));
                var rule = view.requirements().stream().filter(node -> field.equals(node.field()) || field.equals(node.code()))
                        .findFirst().orElse(null);
                UUID id = UUID.randomUUID();
                String task = "guide-" + section;
                update(connection, """
                        INSERT INTO app_acquisition_request(id,project_id,section,act,field,task_id,creator_id)
                        VALUES (?,?,?,?,?,?,?)
                        """,id,project,section,act.name(),field,task,scope.actor().id());
                request = new Request(id,section,act,field,task,scope.actor().id());
                plan = new Plan(setup,need.label(),need.reason(),view.guideRevision(),view.packId(),
                        rule == null ? null : rule.code(),rule == null ? List.of() : rule.dependencies());
            } else {
                var previous = current.plan();
                plan = new Plan(setup,previous.label(),previous.reason(),previous.guideVersion(),previous.packId(),
                        previous.requirementCode(),previous.dependencies());
            }
            write(connection,scope,request.id(),expected+1,plan,Status.OPEN,null);
            return item(scope,request,latest(connection,request.id()));
        });
    }

    Item reply(String token, String project, UUID id, String etag, Reply data) {
        int expected = PilotCommands.expected(etag);
        return scopes.write(token,project,"SAVE_GUIDE",(connection,scope) -> {
            var request = require(connection,scope,id);
            readiness.view(connection,scope,request.section(),request.act());
            var latest = latest(connection,id);
            if (!visible(scope,request,latest) || !canReply(scope,request,latest)) throw invisible();
            var reply = normalize(data);
            if (isRetry(scope,latest,expected,reply.status()) && reply.equals(latest.reply())) return item(scope,request,latest);
            PilotCommands.requireVersion(expected,latest.number());
            write(connection,scope,id,expected+1,latest.plan(),reply.status(),reply);
            return item(scope,request,latest(connection,id));
        });
    }

    List<Revision> history(String token, String project, UUID id) {
        return scopes.read(token,project,"VIEW_GUIDES",(connection,scope) -> {
            var request = require(connection,scope,id);
            readiness.view(connection,scope,request.section(),request.act());
            var latest = latest(connection,id);
            if (!visible(scope,request,latest)) throw invisible();
            return query(connection, REVISION_SQL + " ORDER BY version DESC",this::revision,id).stream()
                    .filter(revision -> visible(scope,request,revision)).map(revision -> project(scope,revision)).toList();
        });
    }

    private static boolean isRetry(DynamicProjectScope.Context scope, Revision current, int expected, Status status) {
        return current != null && current.number() == expected + 1 && status == current.status()
                && scope.actor().id().equals(current.actor());
    }

    static Request require(Connection connection, DynamicProjectScope.Context scope, UUID id) throws SQLException {
        var result = first(connection, """
                SELECT id,section,act,field,task_id,creator_id FROM app_acquisition_request
                WHERE id=? AND project_id=?
                """, AcquisitionRequests::request,id,scope.id());
        if (result == null) throw invisible();
        return result;
    }

    static boolean visible(DynamicProjectScope.Context scope, Request request, Revision current) {
        return current != null && (scope.owner() || scope.actor().id().equals(request.creator())
                || scope.actor().id().equals(current.assignee()));
    }

    private static boolean canPlan(DynamicProjectScope.Context scope, Request request) {
        return scope.actions().contains("SAVE_GUIDE") && (scope.owner() || scope.actor().id().equals(request.creator()));
    }

    private static boolean canReply(DynamicProjectScope.Context scope, Request request, Revision revision) {
        return scope.actions().contains("SAVE_GUIDE") && (revision.assignee() == null
                ? canPlan(scope,request) : scope.actor().id().equals(revision.assignee()));
    }

    private static Item item(DynamicProjectScope.Context scope, Request request, Revision current) {
        return new Item(request,project(scope,current),canPlan(scope,request),canReply(scope,request,current));
    }

    private static Revision project(DynamicProjectScope.Context scope, Revision revision) {
        if (scope.owner()) return revision;
        var plan = revision.plan();
        return new Revision(revision.number(),revision.actor(),revision.assignee(),revision.status(),
                new Plan(plan.setup(),plan.label(),plan.reason(),plan.guideVersion(),plan.packId(),plan.requirementCode(),List.of()),
                revision.reply(),revision.recordedAt());
    }

    private static List<Recipient> recipients(Connection connection, DynamicProjectScope.Context scope, String section) throws SQLException {
        var ids = query(connection, """
                SELECT DISTINCT i.id, i.display_name FROM app_project_collaborator_grant g
                JOIN app_identity i ON i.id=g.identity_id WHERE g.project_id=? AND i.status='ACTIVE' AND i.email_verified_at IS NOT NULL
                    AND g.revoked_at IS NULL AND g.valid_from<=statement_timestamp() AND g.valid_until>statement_timestamp()
                    AND g.role='PREP' ORDER BY i.display_name,i.id
                """, row -> new Recipient(row.getObject(1,UUID.class),row.getString(2)),scope.id());
        var recipients = new java.util.ArrayList<Recipient>();
        for (var candidate : ids) {
            if (eligible(connection,scope,section,candidate.id())) recipients.add(candidate);
        }
        return List.copyOf(recipients);
    }

    private static boolean eligible(Connection connection, DynamicProjectScope.Context scope, String section,
                                    UUID recipient) throws SQLException {
        String activity = first(connection, "SELECT activity FROM app_technical_guide WHERE id=?",
                row -> row.getString(1),section);
        var access = ProjectCollaboration.access(connection,scope.id(),recipient);
        Boolean active = first(connection,"SELECT true FROM app_identity WHERE id=? AND status='ACTIVE' AND email_verified_at IS NOT NULL",
                row -> row.getBoolean(1),recipient);
        return Boolean.TRUE.equals(active) && "PREP".equals(access.role()) && access.actions().contains("GUIDE_" + activity);
    }

    Revision latest(Connection connection, UUID id) throws SQLException {
        return first(connection, REVISION_SQL + " ORDER BY version DESC LIMIT 1",this::revision,id);
    }

    private static final String REVISION_SQL = """
            SELECT version,actor_id,assignee_id,status,plan::text,reply::text,recorded_at
            FROM app_acquisition_revision WHERE request_id=?
            """;

    private static Request request(java.sql.ResultSet row) throws SQLException {
        return new Request(row.getObject(1,UUID.class),row.getString(2),RequirementResolver.Act.valueOf(row.getString(3)),
                row.getString(4),row.getString(5),row.getObject(6,UUID.class));
    }

    private Revision revision(java.sql.ResultSet row) throws SQLException {
        return new Revision(row.getInt(1),row.getObject(2,UUID.class),row.getObject(3,UUID.class),Status.valueOf(row.getString(4)),
                decode(row.getString(5),Plan.class),decode(row.getString(6),Reply.class),row.getObject(7,OffsetDateTime.class));
    }

    private void write(Connection connection, DynamicProjectScope.Context scope, UUID id, int version,
                       Plan plan, Status status, Reply reply) throws SQLException {
        update(connection, """
                INSERT INTO app_acquisition_revision(request_id,version,actor_id,assignee_id,status,plan,reply)
                VALUES (?,?,?,?,?,?::jsonb,?::jsonb)
                """,id,version,scope.actor().id(),plan.setup().assignee(),status.name(),
                json.valueToTree(plan).toString(),reply == null ? null : json.valueToTree(reply).toString());
    }

    private <T> T decode(String data, Class<T> type) {
        if (data == null) return null;
        try {
            return json.readValue(data,type);
        } catch (JsonProcessingException problem) {
            throw new IllegalStateException("Solicitação de dados inválida",problem);
        }
    }

    private static Setup normalize(Setup input) {
        if (input == null || input.kind() == null || input.criteria() == null) throw invalid("plan","Confirme a finalidade e os critérios do pedido.");
        var criteria = input.criteria();
        return new Setup(input.kind(),text(input.purpose(),true),text(input.candidateSource(),false),input.due(),
                input.assignee(),new Criteria(text(criteria.unit(),false),text(criteria.period(),false),
                        text(criteria.boundary(),false),text(criteria.origin(),false),text(criteria.disclosure(),false)));
    }

    private static Reply normalize(Reply input) {
        if (input == null || input.status() == null || input.status() == Status.OPEN
                || (input.status() == Status.PARTIAL) != (input.nature() != null)) {
            throw invalid("reply","Escolha uma resposta e indique a natureza de dados parciais.");
        }
        return new Reply(input.status(),input.nature(),text(input.note(),input.status() == Status.PARTIAL));
    }

    private static String text(String value, boolean required) {
        String result = Objects.toString(value,"").strip();
        if (result.length() > 800 || required && result.isBlank()) {
            throw invalid("text","Informe os campos obrigatórios em até 800 caracteres.");
        }
        return result.isEmpty() ? null : result;
    }

    private static PilotProblem invalid(String field, String message) {
        return new PilotProblem(422,"ACQUISITION_INPUT_INVALID",field,"ECA-004",message);
    }
    private static PilotProblem invisible() {
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","request","ECA-004","Solicitação indisponível para esta conta.");
    }
}
