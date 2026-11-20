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

/** Dynamic MR preparation only. Not the legacy PIL-SYN monitoring closure. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMonitoringDossiersResource {
    private final OpenMonitoringDossiers dossiers;
    OpenMonitoringDossiersResource(OpenMonitoringDossiers dossiers) {
        this.dossiers=dossiers;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project) {
        return Response.ok(dossiers.list(cookie,project))
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }

    @PUT
    @Path("/{period}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bind(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period,
                         @HeaderParam("If-Match") String match,
                         OpenMonitoringDossiers.Input input) {
        var result=dossiers.bind(cookie,project,period,match,input);
        return Response.ok(result).tag(Integer.toString(result.reference().version()))
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }
}
