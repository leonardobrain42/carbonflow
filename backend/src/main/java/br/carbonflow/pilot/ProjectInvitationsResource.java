package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/invitations")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectInvitationsResource {
    private final ProjectInvitations invitations;

    ProjectInvitationsResource(ProjectInvitations invitations) {
        this.invitations = invitations;
    }

    @GET
    public Response owner(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return response(invitations.ownerView(token, project));
    }

    @POST
    @Path("/plans/{plan}/send")
    public Response send(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("plan") UUID plan, @HeaderParam("If-Match") String version,
                         @HeaderParam("Idempotency-Key") String key) {
        return response(invitations.send(token, project, plan, version, key));
    }

    @POST
    @Path("/{invitation}/revoke")
    public Response revoke(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("invitation") UUID id) {
        return response(invitations.revoke(token, project, id));
    }

    private static Response response(Object value) {
        return Response.ok(value).header("Cache-Control", "private, no-store").build();
    }
}
