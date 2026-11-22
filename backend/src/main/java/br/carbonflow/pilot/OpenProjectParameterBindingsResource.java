package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Project parameter pins. Staff publishes the reference. The project reads
 * status without receiving a licensed factor number or an E8 result.
 */
@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class OpenProjectParameterBindingsResource {
    private final OpenProjectParameterBindings service;

    OpenProjectParameterBindingsResource(OpenProjectParameterBindings service) {
        this.service = service;
    }

    @GET
    @Path("/platform/open-parameter-bindings/options")
    public Response options(@CookieParam(AccountAccess.COOKIE) String cookie) {
        return secure(service.options(cookie)).build();
    }

    @POST
    @Path("/platform/open-parameter-bindings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response pin(@CookieParam(AccountAccess.COOKIE) String cookie,
                        @HeaderParam("Idempotency-Key") String key,
                        @HeaderParam("If-Match") String match,
                        OpenProjectParameterBindings.Input input) {
        var result = service.pin(cookie, key, match, input);
        return secure(result.view()).status(result.created() ? 201 : 200)
            .tag(Integer.toString(result.view().version())).build();
    }

    private static Response.ResponseBuilder secure(Object data) {
        return Response.ok(data)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff");
    }
}

@Path("/v1/projects/{project}/technical/open-parameter-bindings")
@Produces(MediaType.APPLICATION_JSON)
class OpenProjectParameterBindingsProjectResource {
    private final OpenProjectParameterBindings service;

    OpenProjectParameterBindingsProjectResource(OpenProjectParameterBindings service) {
        this.service = service;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project) {
        return Response.ok(service.list(cookie, project))
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff")
            .build();
    }
}
