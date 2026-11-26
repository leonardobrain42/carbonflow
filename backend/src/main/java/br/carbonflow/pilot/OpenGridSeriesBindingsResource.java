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
import java.util.UUID;

/** Derives the project's hourly EFel candidate from the platform series; never admits anything. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/grid-series-bindings")
@Produces(MediaType.APPLICATION_JSON)
public class OpenGridSeriesBindingsResource {
    private final OpenGridSeriesBindings service;

    OpenGridSeriesBindingsResource(OpenGridSeriesBindings service) {
        this.service = service;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response bind(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                         @PathParam("period") String period, @HeaderParam("If-Match") String match,
                         @HeaderParam("Idempotency-Key") String key, OpenGridSeriesBindings.Input input) {
        var result = service.bind(cookie, project, period, match, key, input);
        return Response.status(201).entity(result).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").tag(Integer.toString(result.candidate().version())).build();
    }

    @GET
    @Path("/{candidate}")
    public Response get(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                        @PathParam("period") String period, @PathParam("candidate") UUID candidate) {
        return Response.ok(service.get(cookie, project, period, candidate)).header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").build();
    }
}
