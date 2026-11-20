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

/** Administrative, MFA-protected publication of metadata; NOT E8 admission. */
@Path("/v1/platform/e8-fleet-selections")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8FleetSelectionResource {
    private final OpenE8FleetSelections service;
    OpenE8FleetSelectionResource(OpenE8FleetSelections service){this.service=service;}

    @GET
    @Path("options")
    public Response options(@CookieParam(AccountAccess.COOKIE) String cookie) {
        return safe(service.options(cookie)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response assign(@CookieParam(AccountAccess.COOKIE) String cookie,
            @HeaderParam("Idempotency-Key") String key,
            @HeaderParam("If-Match") String match,
            OpenE8FleetSelections.Input input) {
        var selection=service.assign(cookie,key,match,input);
        return safe(selection).status(201).tag(Integer.toString(selection.version())).build();
    }

    private static Response.ResponseBuilder safe(Object data) {
        return Response.ok(data).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}

/** Customer-side lookup of the versioned metadata explicitly published by staff. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-fleet-selections")
@Produces(MediaType.APPLICATION_JSON)
class OpenE8FleetSelectionProjectResource {
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
