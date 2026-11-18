package br.carbonflow.pilot;

import java.net.URI;

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

@Path("/v1/projects/{project}/technical")
@Produces(MediaType.APPLICATION_JSON)
public class DynamicProjectTechnicalResource {
    private final DynamicProjectTechnical technical;

    DynamicProjectTechnicalResource(DynamicProjectTechnical technical) {
        this.technical = technical;
    }

    @GET
    @Path("/capabilities")
    public Response capabilities(@CookieParam(AccountAccess.COOKIE) String cookie,
                                 @PathParam("project") String project) {
        return Response.ok(technical.capabilities(cookie, project))
                .header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/intake")
    public Response intake(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project) {
        var state = technical.intake(cookie, project);
        return Response.ok(state).tag(Integer.toString(state.version()))
                .header("Cache-Control", "private, no-store").build();
    }

    @PUT
    @Path("/intake")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveIntake(@CookieParam(AccountAccess.COOKIE) String cookie,
                               @PathParam("project") String project,
                               @HeaderParam("Idempotency-Key") String key,
                               @HeaderParam("If-Match") String ifMatch,
                               DynamicProjectTechnical.IntakeData data) {
        var saved = technical.saveIntake(cookie, project, key, ifMatch, data);
        return Response.ok(saved).tag(Integer.toString(saved.path("version").asInt()))
                .header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/preliminary-dossier")
    public Response latestDossier(@CookieParam(AccountAccess.COOKIE) String cookie,
                                  @PathParam("project") String project) {
        var view = technical.dossier(cookie, project);
        if (view == null) return Response.noContent().header("Cache-Control", "private, no-store").build();
        return Response.ok(view).tag(Integer.toString(view.revision()))
                .header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/preliminary-dossier/download")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response downloadDossier(@CookieParam(AccountAccess.COOKIE) String cookie,
                                    @PathParam("project") String project) {
        return Response.ok(technical.downloadDossier(cookie, project), MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"dossie-preliminar.json\"")
                .header("Cache-Control", "private, no-store").header("X-Content-Type-Options", "nosniff").build();
    }

    @POST
    @Path("/preliminary-dossier")
    public Response prepareDossier(@CookieParam(AccountAccess.COOKIE) String cookie,
                                   @PathParam("project") String project,
                                   @HeaderParam("Idempotency-Key") String key,
                                   @HeaderParam("If-Match") String ifMatch) {
        var prepared = technical.prepareDossier(cookie, project, key, ifMatch);
        return Response.created(URI.create("/v1/projects/" + project + "/technical/preliminary-dossier"))
                .tag(Integer.toString(prepared.path("revision").asInt()))
                .header("Cache-Control", "private, no-store")
                .entity(prepared).build();
    }
}
