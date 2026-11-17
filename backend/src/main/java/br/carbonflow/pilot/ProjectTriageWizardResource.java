package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/triage")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectTriageWizardResource {
    private final ProjectTriageWizard wizard;

    ProjectTriageWizardResource(ProjectTriageWizard wizard) {
        this.wizard = wizard;
    }

    @GET
    public Response current(@CookieParam(AccountAccess.COOKIE) String token,
                            @PathParam("project") String project) {
        var state = wizard.get(token, project);
        return Response.ok(state).tag(Integer.toString(state.version()))
                .header("Cache-Control", "private, no-store").build();
    }

    @PUT
    @Path("/steps/{step}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token,
                         @PathParam("project") String project,
                         @PathParam("step") int step,
                         @HeaderParam("Idempotency-Key") String key,
                         @HeaderParam("If-Match") String version,
                         ProjectTriageWizard.Mutation input) {
        var result = wizard.save(token, project, step, key, version, input);
        return Response.ok(result).tag(Integer.toString(result.path("version").asInt()))
                .header("Cache-Control", "private, no-store").build();
    }

    @PATCH
    @Path("/cursor")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response move(@CookieParam(AccountAccess.COOKIE) String token,
                         @PathParam("project") String project,
                         @HeaderParam("Idempotency-Key") String key,
                         @HeaderParam("If-Match") String version,
                         ProjectTriageWizard.Cursor input) {
        var result = wizard.move(token, project, key, version, input);
        return Response.ok(result).tag(Integer.toString(result.path("version").asInt()))
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/examples/bev-df-2027")
    public Response example(@CookieParam(AccountAccess.COOKIE) String token,
                            @PathParam("project") String project,
                            @HeaderParam("Idempotency-Key") String key,
                            @HeaderParam("If-Match") String version) {
        var result = wizard.example(token, project, key, version);
        return Response.ok(result).tag(Integer.toString(result.path("version").asInt()))
                .header("Cache-Control", "private, no-store").build();
    }
}
