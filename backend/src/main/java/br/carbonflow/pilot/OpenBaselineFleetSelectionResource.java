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

/** Administrative, MFA-protected EX ANTE fleet reference for the PD (BL-EX-01); NOT E8 admission, NOT a monitored selection. */
@Path("/v1/platform/open-baseline-fleet-selections")
@Produces(MediaType.APPLICATION_JSON)
public class OpenBaselineFleetSelectionResource {
    private final OpenBaselineFleetSelections service;
    OpenBaselineFleetSelectionResource(OpenBaselineFleetSelections service){this.service=service;}

    @GET
    @Path("options")
    public Response options(@CookieParam(AccountAccess.COOKIE) String cookie) {
        return safe(service.options(cookie)).build();
    }

    @GET
    @Path("{project}")
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,@PathParam("project") String project) {
        return safe(service.list(cookie,project)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response assign(@CookieParam(AccountAccess.COOKIE) String cookie,
            @HeaderParam("Idempotency-Key") String key,
            @HeaderParam("If-Match") String match,
            OpenBaselineFleetSelections.Input input) {
        var selection=service.assign(cookie,key,match,input);
        return safe(selection).status(201).tag(Integer.toString(selection.version())).build();
    }

    private static Response.ResponseBuilder safe(Object data) {
        return Response.ok(data).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
