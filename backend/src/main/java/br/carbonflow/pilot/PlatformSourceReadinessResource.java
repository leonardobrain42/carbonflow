package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/source-readiness")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformSourceReadinessResource {
    private final PlatformSourceReadiness readiness;

    PlatformSourceReadinessResource(PlatformSourceReadiness readiness) {
        this.readiness = readiness;
    }

    @GET
    @Path("/{methodology}")
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
                        @PathParam("methodology") String methodology) {
        return Response.ok(readiness.read(token, methodology))
                .header("Cache-Control", "private, no-store").build();
    }
}
