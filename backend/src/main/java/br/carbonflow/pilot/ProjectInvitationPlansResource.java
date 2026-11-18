package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/invitation-plans")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectInvitationPlansResource {
    private final ProjectInvitationPlans plans;

    ProjectInvitationPlansResource(ProjectInvitationPlans plans) {
        this.plans = plans;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return response(plans.get(token, project));
    }

    @PUT
    @Path("/{plan}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("plan") UUID id, @HeaderParam("If-Match") String version,
                         ProjectInvitationPlans.Input input) {
        return response(plans.save(token, project, id, version, input));
    }

    private static Response response(ProjectInvitationPlans.View view) {
        return Response.ok(view).header("Cache-Control", "private, no-store").build();
    }
}
