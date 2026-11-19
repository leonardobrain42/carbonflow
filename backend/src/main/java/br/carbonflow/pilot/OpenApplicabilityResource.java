package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Real tenant-scoped declarations; not a Verra or VVB verdict. */
@Path("/v1/projects/{project}/technical/open-applicability")
@Produces(MediaType.APPLICATION_JSON)
public class OpenApplicabilityResource {
    private final OpenApplicability service;
    OpenApplicabilityResource(OpenApplicability service){this.service=service;}

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project) {
        var value=service.get(token,project);
        return Response.ok(value).tag(Integer.toString(value.version()))
            .header("Cache-Control","private, no-store").build();
    }

    @PUT
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project,
            @HeaderParam("If-Match") String match,
            @HeaderParam("Idempotency-Key") String key,
            OpenApplicability.Input input) {
        var value=service.save(token,project,match,key,input);
        return Response.ok(value).tag(Integer.toString(value.version()))
            .header("Cache-Control","private, no-store").build();
    }
}
