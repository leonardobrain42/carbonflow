package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** The guide and the market test stay outside the additionality opinion. */
@Path("/v1/projects/{project}/technical/open-additionality")
@Produces(MediaType.APPLICATION_JSON)
public class OpenAdditionalityResource {
    private final OpenAdditionality service;

    OpenAdditionalityResource(OpenAdditionality service) {
        this.service = service;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project) {
        return Response.ok(service.get(token, project))
            .header("Cache-Control", "private, no-store")
            .build();
    }

    /** Independent internal opinion on one criterion. It never concludes additionality. */
    @POST
    @Path("/{criterion}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project, @PathParam("criterion") String criterion,
            @HeaderParam("If-Match") String match, @HeaderParam("Idempotency-Key") String key,
            OpenAdditionality.ReviewInput input) {
        var result = service.review(token, project, criterion, match, key, input);
        return Response.status(result.created() ? 201 : 200).entity(result.view())
            .header("Cache-Control", "private, no-store")
            .build();
    }
}
