package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/** Platform administration only (staff, MFA, rosters). The series never admits a factor. */
@Path("/v1/platform/grid-series")
@Produces(MediaType.APPLICATION_JSON)
public class GridSeriesResource {
    private final GridSeries series;

    GridSeriesResource(GridSeries series) {
        this.series = series;
    }

    private static Response ok(Object body) {
        return Response.ok(body).header("Cache-Control", "private, no-store").build();
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token) {
        return ok(series.list(token));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importSeries(@CookieParam(AccountAccess.COOKIE) String token, GridSeries.ImportInput input) {
        var saved = series.importSeries(token, input);
        return Response.status(201).entity(saved).header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/{id}/values")
    public Response values(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           @QueryParam("month") int month) {
        return ok(series.values(token, id, month));
    }

    @POST
    @Path("/{id}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           GridSeries.ReviewInput input) {
        return ok(series.review(token, id, input));
    }
}
