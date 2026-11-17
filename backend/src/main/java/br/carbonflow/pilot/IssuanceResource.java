package br.carbonflow.pilot;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static br.carbonflow.pilot.PilotAccess.*;
import static br.carbonflow.pilot.PilotCommands.*;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class IssuanceResource {
    private final PilotAccess access;
    private final IssuanceLedger ledger;
    private final IssuanceWorkflow workflow;

    IssuanceResource(PilotAccess access, IssuanceLedger ledger, IssuanceWorkflow workflow) {
        this.access = access;
        this.ledger = ledger;
        this.workflow = workflow;
    }

    @GET
    @Path("/issuances/options")
    public Map<String, Object> options(@PathParam("project") String project) {
        access.require(project, "OPCOES_EMISSAO", null, PP, PREP, REV);
        return IssuanceWorkflow.options();
    }

    @GET
    @Path("/verifications")
    public List<IssuanceModel.Verification> verifications(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_VERIFICACOES", null, PP, PREP, REV);
        return ledger.list(project);
    }

    @POST
    @Path("/verifications/{id}/activation-evidence")
    public JsonNode activate(@PathParam("project") String project, @PathParam("id") UUID id,
                             @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                             IssuanceModel.ActivationInput input) {
        var scope = proponent(project, "COMPROVAR_ATIVACAO", id);
        return ledger.activate(scope, access.actor().id(), key(commandKey), id, expected(version), input);
    }

    @GET
    @Path("/verifications/{id}/positions")
    public List<IssuanceModel.Position> positions(@PathParam("project") String project, @PathParam("id") UUID id) {
        access.require(project, "CONSULTAR_POSICOES", id.toString(), PP, PREP, REV);
        return ledger.history(project, id);
    }

    @POST
    @Path("/verifications/{id}/positions")
    public Response position(@PathParam("project") String project, @PathParam("id") UUID id,
                             @HeaderParam("Idempotency-Key") String commandKey, IssuanceModel.PositionInput input) {
        var scope = proponent(project, "REGISTRAR_POSICAO", id);
        return response(201, ledger.position(scope, access.actor().id(), key(commandKey), id, input));
    }

    @GET
    @Path("/issuances")
    public List<IssuanceModel.Snapshot> issuances(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_EMISSOES", null, PP, PREP, REV);
        return workflow.list(project);
    }

    @GET
    @Path("/issuances/{id}/history")
    public List<PilotAudit.Event> history(@PathParam("project") String project, @PathParam("id") UUID id) {
        access.require(project, "CONSULTAR_TRILHA_EMISSAO", id.toString(), PP, PREP, REV);
        return workflow.history(project, id);
    }

    @POST
    @Path("/issuances")
    public Response create(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                           IssuanceModel.Create input) {
        var scope = proponent(project, "PREPARAR_EMISSAO", null);
        return response(201, workflow.create(scope, access.actor().id(), key(commandKey), input));
    }

    @POST
    @Path("/issuances/{id}/channel-checks")
    public JsonNode check(@PathParam("project") String project, @PathParam("id") UUID id,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                          IssuanceModel.ChannelInput input) {
        var scope = proponent(project, "CONFERIR_CANAL_EMISSAO", id);
        return workflow.check(scope, access.actor().id(), key(commandKey), id, expected(version), input);
    }

    @POST
    @Path("/issuances/{id}/actions")
    public Response action(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                           IssuanceModel.Action input) {
        var step = input == null ? "" : String.valueOf(input.transitionId());
        var scope = switch (step) {
            case "COMPARAR" -> access.require(project, "COMPARAR_EMISSAO", id.toString(), PREP);
            case "TX-E3-53", "TX-E3-54" -> access.require(project, "REVISAR_EMISSAO", id.toString(), REV);
            default -> proponent(project, "CAPTURAR_PROVA_EMISSAO", id);
        };
        return response(200, workflow.act(scope, access.actor().id(), key(commandKey), id, expected(version), input));
    }

    private PilotAccess.Scope proponent(String project, String operation, UUID id) {
        String target = id == null ? null : id.toString();
        var scope = access.require(project, operation, target, PP);
        if (scope.powerProof() == null) throw access.denied(project, operation, target, forbidden());
        return scope;
    }

    private static Response response(int success, JsonNode value) {
        return Response.status(value.hasNonNull("conflict") ? 409 : success).entity(value).build();
    }
}
