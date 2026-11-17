package br.carbonflow.pilot;

import java.util.List;
import java.util.UUID;

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

import static br.carbonflow.pilot.PilotAccess.PP;
import static br.carbonflow.pilot.PilotAccess.PREP;
import static br.carbonflow.pilot.PilotAccess.REV;
import static br.carbonflow.pilot.PilotCommands.expected;
import static br.carbonflow.pilot.PilotCommands.key;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class SelectedCalculationResource {
    public record Review(String transitionId, String reason) {}

    private final PilotAccess access;
    private final SelectedRuns runs;
    private final SelectedParameters parameters;

    SelectedCalculationResource(PilotAccess access, SelectedRuns runs, SelectedParameters parameters) {
        this.access = access;
        this.runs = runs;
        this.parameters = parameters;
    }

    @GET
    @Path("/parameters")
    public SelectedCalculation.ParameterCatalogue parameters(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_PARAMETROS", null, PREP, REV, PP);
        return parameters.catalogue(project);
    }

    @POST
    @Path("/parameters")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response proposeParameter(@PathParam("project") String project,
                                     @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                     SelectedCalculation.FactorInput input) {
        var scope = access.require(project, "PROPOR_FATOR", null, PREP);
        return Response.status(201).entity(parameters.propose(scope, access.actor().id(), key(commandKey), expected(version), input)).build();
    }

    @POST
    @Path("/parameters/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode reviewParameter(@PathParam("project") String project, @PathParam("id") String id,
                                    @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                    Review review) {
        var scope = access.require(project, "EXCLUIR_FATOR", id, REV);
        if (review == null || !"EXCLUIR".equals(review.transitionId())) {
            throw SelectedParameters.invalid("ACAO_INVALIDA", "transitionId", "O catálogo sintético permite registrar a exclusão do candidato.");
        }
        return parameters.exclude(scope, access.actor().id(), key(commandKey), id, expected(version), review.reason());
    }

    @GET
    @Path("/runs")
    public List<SelectedCalculation.Run> selectedRuns(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_CALCULOS_SELECAO", null, PREP, REV, PP);
        return runs.list(project);
    }

    @POST
    @Path("/runs/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response reviewRun(@PathParam("project") String project, @PathParam("id") UUID id,
                              @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                              Review review) {
        var scope = access.require(project, "REVISAR_CALCULO_SELECAO", id.toString(), REV);
        if (review == null || review.transitionId() == null) {
            throw SelectedParameters.invalid("ACAO_INVALIDA", "transitionId", "Informe a ação de revisão.");
        }
        return Response.accepted(runs.queue(scope, access.actor().id(), key(commandKey), id, expected(version),
                review.transitionId(), review.reason())).build();
    }

    @GET
    @Path("/runs/{id}/inputs")
    public SelectedCalculation.Inputs inputs(@PathParam("project") String project, @PathParam("id") UUID id) {
        var scope = access.require(project, "CONSULTAR_ENTRADAS_CALCULO", id.toString(), PREP, REV, PP);
        var result = runs.inputs(project, id);
        access.allowed(scope, "CONSULTAR_ENTRADAS_CALCULO", id.toString());
        return result;
    }

    @POST
    @Path("/runs/{id}/reexecute")
    public JsonNode replay(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey) {
        var scope = access.require(project, "REEXECUTAR_SNAPSHOT", id.toString(), PREP, REV);
        return runs.replay(scope, access.actor().id(), key(commandKey), id);
    }
}
