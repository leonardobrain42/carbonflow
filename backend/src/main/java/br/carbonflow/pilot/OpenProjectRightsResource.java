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

/** Project holder and contract records. Neither call grants a role or admits a proof. */
@Path("/v1/projects/{project}/technical/open-rights")
@Produces(MediaType.APPLICATION_JSON)
public class OpenProjectRightsResource {
    private final OpenProjectRights rights;

    OpenProjectRightsResource(OpenProjectRights rights) { this.rights = rights; }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
                        @PathParam("project") String project) {
        return Response.ok(rights.get(token, project)).header("Cache-Control", "private, no-store").build();
    }

    @PUT
    @Path("/parties/{party}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveParty(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project, @PathParam("party") String party,
            @HeaderParam("If-Match") String version, @HeaderParam("Idempotency-Key") String key,
            OpenProjectRights.PartyInput input) {
        return ok(rights.saveParty(token, project, party, version, key, input));
    }

    @PUT
    @Path("/contracts/{contract}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveContract(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project, @PathParam("contract") String contract,
            @HeaderParam("If-Match") String version, @HeaderParam("Idempotency-Key") String key,
            OpenProjectRights.ContractInput input) {
        return ok(rights.saveContract(token, project, contract, version, key, input));
    }

    @PUT
    @Path("/events/{event}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response saveEvent(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("project") String project, @PathParam("event") String event,
            @HeaderParam("If-Match") String version, @HeaderParam("Idempotency-Key") String key,
            OpenProjectRights.EventInput input) {
        return ok(rights.saveEvent(token, project, event, version, key, input));
    }

    private static Response ok(OpenProjectRights.Catalog catalog) {
        return Response.ok(catalog).header("Cache-Control", "private, no-store").build();
    }
}
