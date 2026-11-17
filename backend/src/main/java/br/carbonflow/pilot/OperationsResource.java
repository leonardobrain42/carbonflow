package br.carbonflow.pilot;

import java.time.LocalDate;
import java.util.List;
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
public class OperationsResource {
    private static final String OP = "OP";

    public record Retry(String reason) {}

    private final PilotAccess access;
    private final DurableJobs jobs;
    private final EnergyImports imports;
    private final SelectedRuns runs;
    private final PilotAudit audit;
    private final RetentionPolicy retention;
    private final RecoveryWorkflow recovery;

    OperationsResource(PilotAccess access, DurableJobs jobs, EnergyImports imports, SelectedRuns runs, PilotAudit audit,
                       RetentionPolicy retention, RecoveryWorkflow recovery) {
        this.access = access;
        this.jobs = jobs;
        this.imports = imports;
        this.runs = runs;
        this.audit = audit;
        this.retention = retention;
        this.recovery = recovery;
    }

    @GET
    @Path("/jobs")
    public List<DurableJobs.Summary> jobs(@PathParam("project") String project, @QueryParam("period") String period) {
        var scope = access.require(project, "CONSULTAR_TRABALHOS", null, OP, PREP, REV, PP);
        if (period != null && !List.of("M1", "M2").contains(period))
            throw PilotProblem.invalid("PERIODO_INVALIDO", "period", "OP-E6-13", "Escolha M1 ou M2.");
        return jobs.list(project, period, scope.has(OP) || scope.has(PREP) || scope.has(REV),
                scope.has(PP) || scope.has(PREP) || scope.has(REV));
    }

    @GET
    @Path("/jobs/{id}")
    public Object job(@PathParam("project") String project, @PathParam("id") UUID id) {
        return switch (visibleJob(project, id)) {
            case IMPORTACAO -> imports.report(project, id);
            case CALCULO -> runs.find(project, id).orElseThrow(PilotAccess::invisible);
        };
    }

    @GET
    @Path("/jobs/{id}/attempts")
    public List<DurableJobs.Attempt> attempts(@PathParam("project") String project, @PathParam("id") UUID id) {
        return jobs.history(project, visibleJob(project, id), id);
    }

    @POST
    @Path("/jobs/{id}/retry")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response retry(@PathParam("project") String project, @PathParam("id") UUID id,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version, Retry input) {
        if (visibleJob(project, id) == DurableJobs.Kind.IMPORTACAO) {
            var scope = access.require(project, "RETOMAR_IMPORTACAO_ED_EC", id.toString(), OP, PREP);
            return Response.accepted(imports.resume(scope, access.actor().id(), key(commandKey), id, expected(version))).build();
        }
        var scope = access.require(project, "REVISAR_CALCULO_SELECAO", id.toString(), REV);
        return Response.accepted(runs.queue(scope, access.actor().id(), key(commandKey), id, expected(version),
                "RETOMAR", input == null ? null : input.reason())).build();
    }

    @GET
    @Path("/events")
    public List<PilotAudit.Event> events(@PathParam("project") String project) {
        return audit.events(access.require(project, "CONSULTAR_TRILHA", null, PP, REV), 100);
    }

    @GET
    @Path("/retention")
    public RetentionPolicy.View retention(@PathParam("project") String project, @QueryParam("asOf") LocalDate asOf) {
        access.require(project, "CONSULTAR_RETENCAO", null, OP, PREP, REV, PP);
        return retention.view(project, asOf);
    }

    @POST
    @Path("/retention/credit-end")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode creditEnd(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                              @HeaderParam("If-Match") String version, RetentionPolicy.Correction input) {
        var scope = access.require(project, "REVISAR_FIM_CREDITO_RETENCAO", null, REV);
        return retention.correct(scope, access.actor().id(), key(commandKey), expected(version), input);
    }

    @GET
    @Path("/recovery")
    public Response recovery(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_RECUPERACAO", null, OP, PREP, REV, PP);
        var current = recovery.current(project);
        return current == null ? Response.noContent().build() : Response.ok(current).build();
    }

    @POST
    @Path("/recovery/{id}/check")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode checkRecovery(@PathParam("project") String project, @PathParam("id") UUID id,
                                  @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                  RecoveryWorkflow.Review input) {
        var scope = access.require(project, "CONFERIR_INTERVALO_RECUPERACAO", id.toString(), OP);
        return recovery.review(scope, access.actor().id(), key(commandKey), id, expected(version), input, false);
    }

    @POST
    @Path("/recovery/{id}/release")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode releaseRecovery(@PathParam("project") String project, @PathParam("id") UUID id,
                                    @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                    RecoveryWorkflow.Review input) {
        var scope = access.require(project, "LIBERAR_RECUPERACAO", id.toString(), REV);
        return recovery.review(scope, access.actor().id(), key(commandKey), id, expected(version), input, true);
    }

    private DurableJobs.Kind visibleJob(String project, UUID id) {
        var scope = access.require(project, "CONSULTAR_TRABALHO", id.toString(), OP, PREP, REV, PP);
        var kind = jobs.kind(project, id);
        if (kind == null || kind == DurableJobs.Kind.IMPORTACAO && !scope.has(OP) && !scope.has(PREP) && !scope.has(REV)
                || kind == DurableJobs.Kind.CALCULO && !scope.has(PP) && !scope.has(PREP) && !scope.has(REV)) {
            throw access.denied(project, "CONSULTAR_TRABALHO", id.toString(), invisible());
        }
        return kind;
    }
}
