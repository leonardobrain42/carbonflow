package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/journey")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectWorkResource {
    private final ProjectWork work;

    ProjectWorkResource(ProjectWork work) {
        this.work = work;
    }

    @GET
    public Response view(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project) {
        return Response.ok(work.get(cookie, project)).header("Cache-Control", "private, no-store").build();
    }

    @PUT
    @Path("/resume")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response resume(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project, ProjectWork.Position input) {
        return Response.ok(work.checkpoint(cookie,project,input))
                .header("Cache-Control","private, no-store").build();
    }
}
