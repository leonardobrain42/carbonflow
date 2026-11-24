package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/technical/pd-sections")
@Produces(MediaType.APPLICATION_JSON)
public class PdSectionsResource {
    private final PdSections sections;
    PdSectionsResource(PdSections sections) { this.sections = sections; }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token,@PathParam("project") String project,
                         @QueryParam("period") String period,@QueryParam("run") java.util.UUID run) {
        return Response.ok(sections.list(token,project,period,run)).header("Cache-Control","private, no-store").build();
    }

    @PUT @Path("/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token,@PathParam("project") String project,
                         @PathParam("id") String id,@HeaderParam("If-Match") String match,PdSections.Input input) {
        var saved = sections.save(token,project,id,match,input);
        return Response.ok(saved).tag(Integer.toString(saved.number())).header("Cache-Control","private, no-store").build();
    }

    @GET @Path("/{id}/versions/{version}/download") @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@CookieParam(AccountAccess.COOKIE) String token,@PathParam("project") String project,
                             @PathParam("id") String id,@PathParam("version") int version) {
        var file = sections.download(token,project,id,version);
        return Response.ok(file.text().getBytes(java.nio.charset.StandardCharsets.UTF_8))
                .tag(file.sha256()).header("Content-Disposition","attachment; filename=\"secao-PD-" + id + "-v" + version + ".json\"")
                .header("Cache-Control","private, no-store").header("X-Content-Type-Options","nosniff").build();
    }
}
