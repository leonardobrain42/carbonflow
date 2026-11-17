package br.carbonflow.pilot;

import java.net.URI;

import com.fasterxml.jackson.databind.JsonNode;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Account sandbox project metadata. The legacy GET /v1/projects/{project}
 * continues to belong to the E8 intake; new-project metadata is separate.
 */
@Path("/v1/projects")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectDraftResource {
    public record Creation(java.util.UUID organizationId, String title, String description) {}

    private final ProjectDrafts drafts;

    ProjectDraftResource(ProjectDrafts drafts) {
        this.drafts = drafts;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@CookieParam(AccountAccess.COOKIE) String token,
                           @HeaderParam("Idempotency-Key") String key, Creation input) {
        var created = drafts.create(token, key, input);
        String id = created.path("projectId").asText();
        return Response.created(URI.create("/v1/projects/" + id + "/metadata"))
                .header("Cache-Control", "no-store")
                .tag(Integer.toString(created.path("version").asInt()))
                .entity(created).build();
    }

    @GET
    @Path("/{project}/metadata")
    public Response metadata(@CookieParam(AccountAccess.COOKIE) String token,
                             @PathParam("project") String project) {
        var details = drafts.metadata(token, project);
        return Response.ok(details).header("Cache-Control", "no-store")
                .tag(Integer.toString(details.version())).build();
    }

    @PATCH
    @Path("/{project}/metadata")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response update(@CookieParam(AccountAccess.COOKIE) String token,
                           @PathParam("project") String project,
                           @HeaderParam("Idempotency-Key") String key,
                           @HeaderParam("If-Match") String version,
                           JsonNode changes) {
        var updated = drafts.update(token, project, key, version, changes);
        return Response.ok(updated).header("Cache-Control", "no-store")
                .tag(Integer.toString(updated.path("version").asInt())).build();
    }
}
