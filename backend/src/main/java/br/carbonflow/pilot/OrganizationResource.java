package br.carbonflow.pilot;

import java.net.URI;
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

@Path("/v1/organizations")
@Produces(MediaType.APPLICATION_JSON)
public class OrganizationResource {
    public record Creation(String name) {}

    private final OrganizationBootstrap organizations;

    OrganizationResource(OrganizationBootstrap organizations) {
        this.organizations = organizations;
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@CookieParam(AccountAccess.COOKIE) String token,
                           @HeaderParam("Idempotency-Key") String key, Creation creation) {
        var workspace = organizations.create(token, creation == null ? null : creation.name(), key);
        return Response.created(URI.create("/v1/organizations/" + workspace.id() + "/access"))
                .tag(Integer.toString(workspace.version())).entity(workspace).build();
    }

    @GET
    @Path("/{id}/access")
    public Response access(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id) {
        var workspace = organizations.access(token, id);
        return Response.ok(workspace).tag(Integer.toString(workspace.version())).build();
    }
}
