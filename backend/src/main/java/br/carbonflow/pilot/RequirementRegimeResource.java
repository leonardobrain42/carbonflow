package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/regime")
@Produces(MediaType.APPLICATION_JSON)
public class RequirementRegimeResource {
    private final RegimeSnapshots regimes;

    RequirementRegimeResource(RegimeSnapshots regimes) {
        this.regimes = regimes;
    }

    @POST @Path("/preview") @Consumes(MediaType.APPLICATION_JSON)
    public Response preview(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                            RequirementResolver.Input input) {
        return privateResponse(regimes.preview(cookie,project,input));
    }

    @POST @Path("/snapshots") @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                         RequirementResolver.Input input) {
        return privateResponse(regimes.save(cookie,project,input));
    }

    @GET @Path("/snapshots/{id}")
    public Response get(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                        @PathParam("id") UUID id) {
        return privateResponse(regimes.get(cookie,project,id));
    }

    private static Response privateResponse(Object body) {
        return Response.ok(body).header("Cache-Control","private, no-store").build();
    }
}
