package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Project calculation attempt. The body identifies versions already stored
 * for the period. It does not accept emission numbers and does not admit
 * candidates to E8.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-runs")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8ProjectRunsResource {
    private final OpenE8ProjectRuns runs;

    OpenE8ProjectRunsResource(OpenE8ProjectRuns runs) { this.runs = runs; }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period) {
        return secured(runs.list(cookie, project, period)).build();
    }

    @GET
    @Path("{run}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @PathParam("run") UUID run) {
        return secured(runs.detail(cookie, project, period, run)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @HeaderParam("Idempotency-Key") String key,
                           @HeaderParam("If-Match") String match,
                           OpenE8ProjectRuns.Request request) {
        var outcome = runs.record(cookie, project, period, key, match, request);
        return secured(outcome.view()).status(outcome.created() ? 201 : 200)
            .tag(Integer.toString(outcome.view().version())).build();
    }

    private static Response.ResponseBuilder secured(Object data) {
        return Response.ok(data)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff");
    }
}
