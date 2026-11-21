package br.carbonflow.pilot;

import java.io.InputStream;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Deep link to the synthetic pilot, whose Angular route is distinct from the
 * public '/' landing page. Do not redirect to '/', or the pilot would disappear
 * on reload. Authorization remains exclusively on /v1 APIs.
 */
@Path("/pilot")
public class PilotSpaRouteResource {
    @GET
    public Response openPilot() {
        InputStream page = getClass().getClassLoader().getResourceAsStream("META-INF/resources/index.html");
        if (page == null) return Response.status(Response.Status.NOT_FOUND).build();
        return Response.ok(page).type(MediaType.TEXT_HTML_TYPE)
                .header("Cache-Control", "no-store").build();
    }
}
