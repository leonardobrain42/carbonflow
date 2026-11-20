package br.carbonflow.pilot;

import java.util.UUID;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Customer-side lookup of the versioned metadata explicitly published by staff. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-fleet-selections")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8FleetSelectionProjectResource {
    private final OpenE8FleetSelections service;
    OpenE8FleetSelectionProjectResource(OpenE8FleetSelections service){this.service=service;}

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project,
            @PathParam("period") String period) {
        return safe(service.list(cookie,project,period)).build();
    }

    @GET
    @Path("{selection}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project,
            @PathParam("period") String period,
            @PathParam("selection") UUID selection) {
        return safe(service.detail(cookie,project,period,selection)).build();
    }

    private static Response.ResponseBuilder safe(Object data) {
        return Response.ok(data).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
