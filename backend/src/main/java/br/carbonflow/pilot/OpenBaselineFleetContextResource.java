package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Project-side, read-only view of the EX ANTE fleet reference (BL-EX-02). Confirmation stays with platform staff (BL-EX-01). */
@Path("/v1/projects/{project}/technical/baseline/fleet-context")
@Produces(MediaType.APPLICATION_JSON)
public class OpenBaselineFleetContextResource {
    private final OpenBaselineFleetContext service;
    OpenBaselineFleetContextResource(OpenBaselineFleetContext service){this.service=service;}

    @GET
    public Response read(@CookieParam(AccountAccess.COOKIE) String cookie,@PathParam("project") String project) {
        return Response.ok(service.read(cookie,project)).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }
}
