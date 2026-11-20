package br.carbonflow.pilot;

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

/** Internal, NOT_ADMITTED energy staging, never an executable E8 endpoint. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-energy-candidates")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8EnergyCandidatesResource {
    private final OpenE8EnergyCandidates candidates;
    OpenE8EnergyCandidatesResource(OpenE8EnergyCandidates candidates) {
        this.candidates=candidates;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period) {
        return noStore(candidates.list(cookie,project,period)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prepare(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,
                            @PathParam("period") String period,
                            @HeaderParam("Idempotency-Key") String key,
                            @HeaderParam("If-Match") String match,
                            OpenE8EnergyCandidates.Input input) {
        return noStore(candidates.prepare(cookie,project,period,key,match,input))
            .status(201).build();
    }

    private static Response.ResponseBuilder noStore(Object value) {
        return Response.ok(value).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
