package br.carbonflow.pilot;

import java.net.URI;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.Response;

/**
 * Allows a hard refresh of /pilot while the Angular router owns navigation.
 * Only redirects to the static shell; never grants access to project APIs.
 */
@Path("/pilot")
public class PilotSpaRouteResource {
    @GET
    public Response openPilot() {
        return Response.seeOther(URI.create("/")).build();
    }
}
