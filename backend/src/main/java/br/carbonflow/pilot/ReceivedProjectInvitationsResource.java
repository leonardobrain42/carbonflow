package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/project-invitations")
@Produces(MediaType.APPLICATION_JSON)
public class ReceivedProjectInvitationsResource {
    private final ProjectInvitations invitations;

    ReceivedProjectInvitationsResource(ProjectInvitations invitations) {
        this.invitations = invitations;
    }

    @GET
    public Response received(@CookieParam(AccountAccess.COOKIE) String token) {
        return Response.ok(invitations.received(token)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/{invitation}/accept")
    public Response accept(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("invitation") UUID id) {
        return Response.ok(invitations.accept(token, id)).header("Cache-Control", "private, no-store").build();
    }
}
