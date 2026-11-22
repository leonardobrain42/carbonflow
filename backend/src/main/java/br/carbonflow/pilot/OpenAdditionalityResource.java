package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
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
}
