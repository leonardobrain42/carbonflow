package br.carbonflow.pilot;

import java.io.InputStream;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Serve the existing Angular entrypoint for workspace deep links.
 * Authentication and tenant authorization remain exclusively in /v1 APIs.
 */
@Path("/workspace")
public class WorkspaceSpaRouteResource {
    @GET
    public Response workspace() {
        return html();
    }

    @GET
    @Path("/projects/new")
    public Response newProject() {
        return html();
    }

    private Response html() {
        InputStream page = getClass().getClassLoader().getResourceAsStream("META-INF/resources/index.html");
        if (page == null) {
            return Response.status(Response.Status.NOT_FOUND).build();
        }
        return Response.ok(page).type(MediaType.TEXT_HTML_TYPE)
                .header("Cache-Control", "no-store").build();
    }
}
