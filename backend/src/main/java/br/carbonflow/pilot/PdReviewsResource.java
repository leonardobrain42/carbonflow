package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/technical/pd-reviews")
@Produces(MediaType.APPLICATION_JSON)
public class PdReviewsResource {
    private final PdReviews reviews;
    PdReviewsResource(PdReviews reviews) { this.reviews = reviews; }

    @GET
    public Response queue(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        return response(reviews.queue(token, project));
    }

    @GET @Path("/{id}/versions/{version}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                            @PathParam("id") String id, @PathParam("version") int version) {
        return response(reviews.history(token, project, id, version));
    }

    @POST @Path("/{id}/versions/{version}") @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("id") String id, @PathParam("version") int version,
                           @HeaderParam("If-Match") String match, PdReviews.Decision input) {
        return response(reviews.decide(token, project, id, version, match, input));
    }

    private static Response response(Object result) {
        return Response.ok(result).header("Cache-Control","private, no-store").build();
    }
}
