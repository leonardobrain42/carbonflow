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

/** Preliminary TOOL07 Simple OM A1, never normatively admitted to E8. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/tool07-simple-om-a1/{workbook}/runs")
@Produces(MediaType.APPLICATION_JSON)
public class OpenTool07SimpleOmRunsResource {
    private final OpenTool07SimpleOmRuns service;
    OpenTool07SimpleOmRunsResource(OpenTool07SimpleOmRuns service){this.service=service;}

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period,
                         @PathParam("workbook") UUID workbook) {
        return safe(service.list(cookie,project,period,workbook)).build();
    }

    @GET
    @Path("{run}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @PathParam("workbook") UUID workbook,
                           @PathParam("run") UUID run) {
        return safe(service.detail(cookie,project,period,workbook,run)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @PathParam("workbook") UUID workbook,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenTool07SimpleOmRuns.Input payload) {
        var result=service.submit(cookie,project,period,workbook,match,key,payload);
        return safe(result).status(201).tag(Integer.toString(result.version())).build();
    }

    private static Response.ResponseBuilder safe(Object entity) {
        return Response.ok(entity).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
