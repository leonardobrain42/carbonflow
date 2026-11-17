package br.carbonflow.pilot;

import java.util.List;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import static br.carbonflow.pilot.PilotAccess.*;
import static br.carbonflow.pilot.IntakeModel.*;
import static br.carbonflow.pilot.PilotCommands.*;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ProjectIntakeResource {
    private static final String OP = "OP";
    public record Action(String transitionId, JsonNode details) {}

    private final PilotAccess access;
    private final ProjectIntake service;
    private final IntakeCatalog catalog;
    private final ObjectMapper mapper;

    ProjectIntakeResource(PilotAccess access, ProjectIntake service, IntakeCatalog catalog, ObjectMapper mapper) {
        this.access = access;
        this.service = service;
        this.catalog = catalog;
        this.mapper = mapper;
    }

    @GET
    public ProjectIntake.Status status(@PathParam("project") String project) {
        var scope = internal(project, "CONSULTAR_ENQUADRAMENTO");
        var status = service.status(scope);
        access.allowed(scope, "CONSULTAR_ENQUADRAMENTO", null);
        return status;
    }

    @GET
    @Path("/intake/examples")
    public IntakeCatalog.Examples examples(@PathParam("project") String project) {
        var scope = internal(project, "CONSULTAR_AMOSTRA_INICIAL");
        access.allowed(scope, "CONSULTAR_AMOSTRA_INICIAL", null);
        return catalog.examples();
    }

    @GET
    @Path("/intake/history")
    public List<ProjectIntake.History> history(@PathParam("project") String project) {
        var scope = internal(project, "CONSULTAR_HISTORICO_INICIAL");
        access.allowed(scope, "CONSULTAR_HISTORICO_INICIAL", null);
        return service.history(scope);
    }

    @GET
    @Path("/intake/versions/{version}")
    public ProjectIntake.Receipt historyVersion(@PathParam("project") String project, @PathParam("version") int version) {
        var scope = internal(project, "CONSULTAR_VERSAO_INICIAL");
        String target = Integer.toString(version);
        try {
            var item = service.version(scope, version);
            access.allowed(scope, "CONSULTAR_VERSAO_INICIAL", target);
            return item;
        } catch (PilotProblem problem) {
            throw access.denied(project, "CONSULTAR_VERSAO_INICIAL", target, problem);
        }
    }

    @POST
    @Path("/intake/{id}/actions")
    public JsonNode intake(@PathParam("project") String project, @PathParam("id") String id,
                           @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                           Action action) {
        String transition = transition(action);
        return switch (transition) {
            case "TX-E3-01" -> service.propose(forAction(project, transition, id, PREP), access.actor().id(),
                    key(commandKey), expected(version), details(action, ProjectIntake.Proposal.class));
            case "TX-E3-02" -> service.review(forAction(project, transition, id, REV), access.actor().id(),
                    key(commandKey), expected(version), details(action, ProjectIntake.Review.class));
            default -> throw badRequest();
        };
    }

    @POST
    @Path("/implantations/{id}/actions")
    public JsonNode implantation(@PathParam("project") String project, @PathParam("id") String id,
                                 @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                 Action action) {
        String transition = transition(action);
        return switch (transition) {
            case "TX-E3-03" -> service.commit(forAction(project, transition, id, PP), access.actor().id(),
                    key(commandKey), expected(version), details(action, ProjectIntake.CommitInput.class));
            case "TX-E3-04" -> service.operate(forAction(project, transition, id, OP), access.actor().id(),
                    key(commandKey), expected(version), details(action, ProjectIntake.OperationInput.class));
            default -> throw badRequest();
        };
    }

    @POST
    @Path("/case-records")
    public Response caseRecord(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                               @HeaderParam("If-Match") String version, CaseInput input) {
        if (input == null || input.type() == null) throw badRequest();
        String role = switch (input.type()) {
            case DIREITOS, EXCEDENTE, SOBREPOSICAO, RECLAMACAO -> PP;
            default -> PREP;
        };
        return Response.status(201).entity(service.record(forAction(project, "REGISTRAR_EVIDENCIA_INICIAL", project, role),
                access.actor().id(), key(commandKey), expected(version), input)).build();
    }

    @POST
    @Path("/inventory/{id}/revisions")
    public Response meter(@PathParam("project") String project, @PathParam("id") String circuit,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                          MeterInput input) {
        var scope = access.require(project, "REVISAR_MEDIDOR", circuit, OP);
        return Response.status(201).entity(service.reviseMeter(scope, access.actor().id(), key(commandKey),
                expected(version), circuit, input)).build();
    }

    @POST
    @Path("/inventory/readings")
    public Response reading(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                            Reading input) {
        var scope = access.require(project, "PRECHECAR_LEITURA_EC", null, OP);
        return Response.status(201).entity(service.checkReading(scope, access.actor().id(), key(commandKey), input)).build();
    }

    private PilotAccess.Scope internal(String project, String action) {
        return access.require(project, action, null, PREP, REV, PP, OP);
    }

    private PilotAccess.Scope forAction(String project, String action, String id, String role) {
        var scope = access.require(project, action, id, role);
        if (!project.equals(id)) throw access.denied(project, action, id, PilotAccess.invisible());
        if (PP.equals(role) && scope.powerProof() == null) {
            throw access.denied(project, action, id, PilotAccess.forbidden());
        }
        return scope;
    }

    private String transition(Action action) {
        if (action == null || action.transitionId() == null) throw badRequest();
        return action.transitionId();
    }

    private <T> T details(Action action, Class<T> type) {
        if (action.details() == null || !action.details().isObject()) throw badRequest();
        try {
            return mapper.treeToValue(action.details(), type);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw badRequest();
        }
    }

    private PilotProblem badRequest() {
        return new PilotProblem(400, "PEDIDO_INVALIDO", "command", "OP-E6-01/02", "Confira os campos da ação.");
    }
}

@Path("/v1/projects")
@Produces(MediaType.APPLICATION_JSON)
class ProjectDirectoryResource {
    private final PilotAccess access;

    ProjectDirectoryResource(PilotAccess access) { this.access = access; }

    @GET
    public List<PilotAccess.Scope> projects() { return access.session().projects(); }
}
