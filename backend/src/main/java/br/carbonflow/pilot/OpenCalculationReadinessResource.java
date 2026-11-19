package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Dynamic project endpoint only. No pilot credentials, synthetic factors, or
 * project-facing visibility into platform-staff source candidate inventories.
 */
@Path("/v1/projects/{project}/technical/open-calculation-readiness")
@Produces(MediaType.APPLICATION_JSON)
public class OpenCalculationReadinessResource {
    private final OpenCalculationReadiness service;

    OpenCalculationReadinessResource(OpenCalculationReadiness service) {
        this.service = service;
    }

    @GET
    public Response read(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project) {
        return Response.ok(service.get(cookie, project))
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .build();
    }
}
