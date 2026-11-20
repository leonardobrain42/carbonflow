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

/** Claimed hourly grid-factor sources; internal review is not Verra admission. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/efel-candidates")
@Produces(MediaType.APPLICATION_JSON)
public class OpenEfelCandidatesResource {
    private final OpenEfelCandidates service;
    OpenEfelCandidatesResource(OpenEfelCandidates service){this.service=service;}

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period) {
        return secure(service.list(cookie,project,period)).build();
    }
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenEfelCandidates.Input input) {
        var result=service.submit(cookie,project,period,match,key,input);
        return secure(result).status(201).tag(Integer.toString(result.version())).build();
    }
    @POST
    @Path("/{candidate}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("candidate") UUID candidate,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenEfelCandidates.Decision decision) {
        var result=service.review(cookie,project,candidate,match,key,decision);
        return secure(result).tag(Integer.toString(result.review().version())).build();
    }
    private static Response.ResponseBuilder secure(Object value) {
        return Response.ok(value).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
