package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/sections/{section}/facts")
@Produces(MediaType.APPLICATION_JSON)
public class GuideFactsResource {
    private final GuideFacts facts;

    GuideFactsResource(GuideFacts facts) { this.facts = facts; }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("section") String section) {
        return privateResponse(facts.view(token,project,section));
    }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response propose(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                            @PathParam("section") String section, GuideFacts.Proposal input) {
        return privateResponse(facts.propose(token,project,section,input));
    }

    @POST @Path("/{id}/decision") @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("section") String section, @PathParam("id") UUID id,
                           @HeaderParam("If-Match") String etag, GuideFacts.Decision input) {
        return privateResponse(facts.decide(token,project,section,id,etag,input));
    }

    private static Response privateResponse(Object result) {
        return Response.ok(result).header("Cache-Control","private, no-store").build();
    }
}
