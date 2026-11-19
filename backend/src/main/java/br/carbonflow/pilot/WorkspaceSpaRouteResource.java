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

    @GET
    @Path("/projects/{project}/overview")
    public Response projectOverview() {
        return html();
    }

    @GET
    @Path("/projects/{project}/technical")
    public Response projectTechnical() {
        return html();
    }

    @GET
    @Path("/projects/{project}/triage")
    public Response projectTriage() {
        return html();
    }

    @GET
    @Path("/projects/{project}/experience")
    public Response projectExperience() {
        return html();
    }

    @GET
    @Path("/projects/{project}/invitation-plans")
    public Response invitationPlans() {
        return html();
    }

    @GET
    @Path("/projects/{project}/authority-requests")
    public Response authorityRequests() {
        return html();
    }

    @GET
    @Path("/platform/sources")
    public Response platformSources() {
        return html();
    }

    @GET
    @Path("/compliance")
    public Response compliance() {
        return html();
    }

    @GET
    @Path("/invitations")
    public Response receivedInvitations() {
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
