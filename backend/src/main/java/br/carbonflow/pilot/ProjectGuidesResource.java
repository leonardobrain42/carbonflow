package br.carbonflow.pilot;

import java.util.Map;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/technical/guides")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectGuidesResource {
    private final ProjectGuides guides;

    ProjectGuidesResource(ProjectGuides guides) { this.guides = guides; }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return Response.ok(guides.get(token, project)).header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/{guide}/revisions/{version}")
    public Response revision(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                             @PathParam("guide") String guide, @PathParam("version") int version) {
        return Response.ok(guides.revision(token, project, guide, version)).header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/quantification/revisions/{version}/inputs")
    public Response inputs(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("version") int version) {
        return Response.ok(guides.quantificationInputs(token, project, version)).header("Cache-Control","private, no-store").build();
    }

    @PUT
    @Path("/{guide}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("guide") String id, @HeaderParam("If-Match") String match,
                         @HeaderParam("X-Guide-Definition-Version") String schemaVersion,
                         Map<String,String> answers) {
        var saved = guides.save(token, project, id, match, schemaVersion, answers);
        return Response.ok(saved).tag(Integer.toString(saved.version())).header("Cache-Control","private, no-store").build();
    }
}
