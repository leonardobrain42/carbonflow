package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Platform administration only (staff with MFA and the registry privilege). Metadata, never source content. */
@Path("/v1/platform/methodology-registry")
@Produces(MediaType.APPLICATION_JSON)
public class MethodologyRegistryResource {
    private final MethodologyRegistry registry;

    MethodologyRegistryResource(MethodologyRegistry registry) {
        this.registry = registry;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token) {
        return Response.ok(registry.list(token)).header("Cache-Control", "private, no-store").build();
    }

    /** New key: If-Match "0". New version of a key: If-Match is the current version. Same content: no new version. */
    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@CookieParam(AccountAccess.COOKIE) String token,
                             @HeaderParam("If-Match") String ifMatch, MethodologyRegistry.Input input) {
        var saved = registry.register(token, ifMatch, input);
        return Response.status(saved.created() ? 201 : 200).entity(saved.entry())
                .header("ETag", "\"" + saved.entry().version() + "\"")
                .header("Cache-Control", "private, no-store").build();
    }
}
