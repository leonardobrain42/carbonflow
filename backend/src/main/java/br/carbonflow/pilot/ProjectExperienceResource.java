package br.carbonflow.pilot;

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

@Path("/v1/projects/{project}/experience")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectExperienceResource {
    private final ProjectExperience experience;

    ProjectExperienceResource(ProjectExperience experience) {
        this.experience = experience;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return response(experience.get(token, project));
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @HeaderParam("If-Match") String version, ProjectExperience.Preferences input) {
        return response(experience.save(token, project, version, input));
    }

    private static Response response(ProjectExperience.View view) {
        return Response.ok(view).tag(Integer.toString(view.version()))
                .header("Cache-Control", "private, no-store").build();
    }
}
