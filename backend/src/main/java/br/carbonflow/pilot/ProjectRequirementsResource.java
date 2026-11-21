package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Read-only requirement projection for the technical workspace. Not a verdict. */
@Path("/v1/projects/{project}/technical/requirements")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectRequirementsResource {
    private final ProjectRequirements service;

    ProjectRequirementsResource(ProjectRequirements service) {
        this.service = service;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project) {
        var value = service.get(token, project);
        return Response.ok(value)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .build();
    }
}
