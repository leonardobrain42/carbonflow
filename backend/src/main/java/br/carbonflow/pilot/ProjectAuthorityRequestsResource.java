package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/authority-requests")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectAuthorityRequestsResource {
    private final ProjectAuthorityRequests requests;

    ProjectAuthorityRequestsResource(ProjectAuthorityRequests requests) {
        this.requests = requests;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return response(requests.get(token, project));
    }

    @PUT
    @Path("/{request}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("request") UUID id, ProjectAuthorityRequests.Input input) {
        return response(requests.submit(token, project, id, input));
    }

    @POST
    @Path("/{request}/withdraw")
    public Response withdraw(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                             @PathParam("request") UUID id, @HeaderParam("If-Match") String version) {
        return response(requests.withdraw(token, project, id, version));
    }

    private static Response response(ProjectAuthorityRequests.View view) {
        return Response.ok(view).header("Cache-Control", "private, no-store").build();
    }
}
