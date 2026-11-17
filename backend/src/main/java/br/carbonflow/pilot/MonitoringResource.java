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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import static br.carbonflow.pilot.PilotAccess.*;
import static br.carbonflow.pilot.PilotCommands.*;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class MonitoringResource {
    private static final String OP = "OP";
    private static final Map<String, String> ROLES = Map.of("TX-E3-41", OP, "TX-E3-42", OP, "TX-E3-43", REV,
            "TX-E3-44", PREP, "TX-E3-45", REV, "TX-E3-46", REV);
    private final PilotAccess access;
    private final MonitoringPeriods periods;
    private final MeasuredSplits splits;

    MonitoringResource(PilotAccess access, MonitoringPeriods periods, MeasuredSplits splits) {
        this.access = access;
        this.periods = periods;
        this.splits = splits;
    }

    @GET
    @Path("/periods")
    public List<MonitoringPeriods.Period> list(@PathParam("project") String project) {
        internal(project, "CONSULTAR_PERIODOS", null);
        return periods.list(project);
    }

    @POST
    @Path("/periods")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response propose(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                            @HeaderParam("If-Match") String version, MonitoringPeriods.Proposal proposal) {
        var scope = access.require(project, "PROPOR_PERIODO", null, PREP, PP);
        return Response.status(201).entity(periods.propose(scope, access.actor().id(), key(commandKey), expected(version), proposal)).build();
    }

    @POST
    @Path("/periods/{period}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode act(@PathParam("project") String project, @PathParam("period") String period,
                        @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                        MonitoringPeriods.Action action) {
        if (action == null || action.transitionId() == null || !ROLES.containsKey(action.transitionId())) {
            throw MonitoringPeriods.invalid("ACAO_INVALIDA", "Escolha uma transição da janela.");
        }
        var scope = access.require(project, action.transitionId(), period, ROLES.get(action.transitionId()));
        return periods.act(scope, access.actor().id(), key(commandKey), period(period), expected(version), action);
    }

    @GET
    @Path("/periods/{period}/history")
    public List<MonitoringPeriods.Period> history(@PathParam("project") String project, @PathParam("period") String period) {
        internal(project, "CONSULTAR_HISTORICO_PERIODO", period);
        return periods.history(project, period(period));
    }

    @GET
    @Path("/periods/{period}/coverage")
    public PeriodCoverage.Report coverage(@PathParam("project") String project, @PathParam("period") String period,
                                          @QueryParam("run") UUID run, @QueryParam("version") int version) {
        access.require(project, "CONFERIR_COBERTURA", period, PREP, REV);
        return periods.assess(project, period(period), new EnergyImportModel.Ref(run, version));
    }

    @GET
    @Path("/periods/{period}/closures/{revision}")
    public Response closure(@PathParam("project") String project, @PathParam("period") String period,
                            @PathParam("revision") int revision) {
        internal(project, "CONSULTAR_FECHAMENTO", period);
        var result = periods.closure(project, period(period), revision);
        return Response.ok(result.bytes()).header("X-Closure-SHA256", result.sha256())
                .header("X-Eligible-For-New-Use", result.eligible()).header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/periods/{period}/closures/{revision}/dependents")
    public List<MonitoringPeriods.Dependent> dependents(@PathParam("project") String project, @PathParam("period") String period,
                                                       @PathParam("revision") int revision) {
        internal(project, "CONSULTAR_DEPENDENTES", period);
        return periods.dependents(project, period(period), revision);
    }

    @POST
    @Path("/periods/{period}/dependents")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response link(@PathParam("project") String project, @PathParam("period") String period,
                         @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                         MonitoringPeriods.DependentInput input) {
        var scope = access.require(project, "VINCULAR_DEPENDENTE_FECHAMENTO", period, PP);
        return Response.status(201).entity(periods.link(scope, access.actor().id(), key(commandKey), period(period),
                expected(version), input)).build();
    }

    @POST
    @Path("/observations/{row}/measured-split")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response split(@PathParam("project") String project, @PathParam("row") UUID row,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                          MeasuredSplits.Input input) {
        var scope = access.require(project, "RECONCILIAR_SEGMENTOS_MEDIDOS", row.toString(), OP);
        return Response.status(201).entity(splits.reconcile(scope, access.actor().id(), key(commandKey), row,
                expected(version), input)).build();
    }

    @GET
    @Path("/observations/{row}/measured-split")
    public JsonNode split(@PathParam("project") String project, @PathParam("row") UUID row) {
        internal(project, "CONSULTAR_SEGMENTOS_MEDIDOS", row.toString());
        return splits.get(project, row);
    }

    private void internal(String project, String action, String target) {
        access.require(project, action, target, OP, PP, PREP, REV);
    }

    private static String period(String period) {
        if (!EnergyImports.validPeriod(period)) throw PilotAccess.invisible();
        return period;
    }
}
