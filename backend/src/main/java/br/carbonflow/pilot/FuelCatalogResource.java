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

/** Platform administration only (staff, MFA, rosters). The catalog never admits a factor. */
@Path("/v1/platform/fuel-catalog")
@Produces(MediaType.APPLICATION_JSON)
public class FuelCatalogResource {
    private final FuelCatalog catalog;

    FuelCatalogResource(FuelCatalog catalog) {
        this.catalog = catalog;
    }

    private static Response ok(Object body) {
        return Response.ok(body).header("Cache-Control", "private, no-store").build();
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token) {
        return ok(catalog.catalog(token));
    }

    @POST
    @Path("/fuels")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerFuel(@CookieParam(AccountAccess.COOKIE) String token, FuelCatalog.FuelInput input) {
        return ok(catalog.registerFuel(token, input));
    }

    @POST
    @Path("/entries")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerEntry(@CookieParam(AccountAccess.COOKIE) String token, FuelCatalog.EntryInput input) {
        return ok(catalog.registerEntry(token, input));
    }

    @POST
    @Path("/entries/{id}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id, FuelCatalog.ReviewInput input) {
        return ok(catalog.review(token, id, input));
    }

    @GET
    @Path("/boundary-resolution")
    public Response resolve(@CookieParam(AccountAccess.COOKIE) String token, @QueryParam("boundary") String boundary,
                            @QueryParam("fuelId") String fuelId, @QueryParam("date") String date,
                            @QueryParam("scope") String scope, @QueryParam("gwpSet") String gwpSet) {
        return ok(catalog.resolve(token, boundary, fuelId, date, scope, gwpSet));
    }

    @GET
    @Path("/boundaries")
    public Response boundaries(@CookieParam(AccountAccess.COOKIE) String token) {
        return ok(catalog.boundaries(token));
    }

    @GET
    @Path("/entries/{id}/conversion")
    public Response convert(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                            @QueryParam("targetUnit") String targetUnit) {
        return ok(catalog.convert(token, id, targetUnit));
    }
}
