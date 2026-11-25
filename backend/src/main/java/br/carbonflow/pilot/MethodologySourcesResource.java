package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Platform administration only (staff, MFA, registry privilege). Metadata about expected sources, never content. */
@Path("/v1/platform/methodology-registry/{methodology}/{edition}/sources")
@Produces(MediaType.APPLICATION_JSON)
public class MethodologySourcesResource {
    private final MethodologySources sources;

    MethodologySourcesResource(MethodologySources sources) {
        this.sources = sources;
    }

    @GET
    public Response contexts(@CookieParam(AccountAccess.COOKIE) String token,
                             @PathParam("methodology") String methodology, @PathParam("edition") String edition) {
        return Response.ok(sources.contexts(token, methodology, edition))
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/contexts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerContext(@CookieParam(AccountAccess.COOKIE) String token,
                                    @PathParam("methodology") String methodology,
                                    @PathParam("edition") String edition, MethodologySources.ContextInput input) {
        var saved = sources.registerContext(token, methodology, edition, input);
        return Response.status(saved.created() ? 201 : 200).entity(saved.context())
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/contexts/{scenario}/needs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response registerNeed(@CookieParam(AccountAccess.COOKIE) String token,
                                 @PathParam("methodology") String methodology,
                                 @PathParam("edition") String edition, @PathParam("scenario") String scenario,
                                 MethodologySources.NeedInput input) {
        var saved = sources.registerNeed(token, methodology, edition, scenario, input);
        return Response.status(saved.created() ? 201 : 200).entity(saved.need())
                .header("Cache-Control", "private, no-store").build();
    }
}
