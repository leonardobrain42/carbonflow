package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/source-readiness")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformSourceReadinessResource {
    private final PlatformSourceReadiness readiness;

    PlatformSourceReadinessResource(PlatformSourceReadiness readiness) {
        this.readiness = readiness;
    }

    /** Context discovery is server-driven; adding a methodology requires only catalog data. */
    @GET
    @Path("/contexts")
    public Response contexts(@CookieParam(AccountAccess.COOKIE) String token) {
        return Response.ok(readiness.contexts(token)).header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/{methodology}")
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
                        @PathParam("methodology") String methodology,
                        @QueryParam("edition") String edition,
                        @QueryParam("scenario") String scenario,
                        @QueryParam("stage") String stage,
                        @QueryParam("status") String status,
                        @QueryParam("offset") @DefaultValue("0") int offset,
                        @QueryParam("limit") @DefaultValue("25") int limit) {
        return Response.ok(readiness.read(token, methodology, edition, scenario, stage, status, offset, limit))
                .header("Cache-Control", "private, no-store").build();
    }
}
