package br.carbonflow.pilot;

import java.util.UUID;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class AcquisitionResource {
    private final AcquisitionRequests requests;

    AcquisitionResource(AcquisitionRequests requests) {
        this.requests = requests;
    }

    @GET @Path("/sections/{section}/acquisition/{act}")
    public Response view(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("section") String section, @PathParam("act") RequirementResolver.Act act) {
        return privateResponse(requests.view(token,project,section,act));
    }

    @PUT @Path("/sections/{section}/acquisition/{act}/{field}") @Consumes(MediaType.APPLICATION_JSON)
    public Response plan(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                         @PathParam("section") String section, @PathParam("act") RequirementResolver.Act act,
                         @PathParam("field") String field, @HeaderParam("If-Match") String version, AcquisitionRequests.Setup input) {
        return privateResponse(requests.plan(token,project,section,act,field,version,input));
    }

    @GET @Path("/evidence-requests/{id}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                            @PathParam("id") UUID id) {
        return privateResponse(requests.history(token,project,id));
    }

    @POST @Path("/evidence-requests/{id}/replies") @Consumes(MediaType.APPLICATION_JSON)
    public Response reply(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                          @PathParam("id") UUID id, @HeaderParam("If-Match") String version, AcquisitionRequests.Reply input) {
        return privateResponse(requests.reply(token,project,id,version,input));
    }

    private static Response privateResponse(Object result) {
        return Response.ok(result).header("Cache-Control","private,no-store").build();
    }
}
