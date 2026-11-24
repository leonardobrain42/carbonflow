package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/technical/open-calculation-years/{year}")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8AnnualConsolidationResource {
    private final OpenE8AnnualConsolidation consolidation;

    OpenE8AnnualConsolidationResource(OpenE8AnnualConsolidation consolidation) {
        this.consolidation = consolidation;
    }

    @GET
    public Response read(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project, @PathParam("year") int year) {
        return Response.ok(consolidation.read(cookie, project, year))
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff").build();
    }
}
