package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
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
}
