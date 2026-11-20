package br.carbonflow.pilot;

import java.util.UUID;
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
 * Immutable, project-scoped candidate link between reviewed ED/EC and
 * hourly grid factor. It is NOT a VM0038/E8 execution or admission endpoint.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/e8-input-bindings")
@Produces(MediaType.APPLICATION_JSON)
public class OpenE8InputBindingsResource {
    private final OpenE8InputBindings service;

    OpenE8InputBindingsResource(OpenE8InputBindings service) {
        this.service=service;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project,
            @PathParam("period") String period) {
        return secured(service.list(cookie,project,period)).build();
    }

    @GET
    @Path("{binding}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project,
            @PathParam("period") String period,
            @PathParam("binding") UUID binding) {
        return secured(service.detail(cookie,project,period,binding)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prepare(@CookieParam(AccountAccess.COOKIE) String cookie,
            @PathParam("project") String project,
            @PathParam("period") String period,
            @HeaderParam("Idempotency-Key") String key,
            @HeaderParam("If-Match") String match,
            OpenE8InputBindings.Input input) {
        var output=service.prepare(cookie,project,period,key,match,input);
        return secured(output).status(201).tag(Integer.toString(output.version())).build();
    }

    private static Response.ResponseBuilder secured(Object data) {
        return Response.ok(data)
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
