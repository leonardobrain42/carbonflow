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

/**
 * Single annual EFel candidates. Hourly series stay on the project EFel
 * route. A candidate here is not VT0011 admission and not a project pin.
 */
@Path("/v1/platform/open-grid-factors")
@Produces(MediaType.APPLICATION_JSON)
public class OpenGridFactorCandidatesResource {
    private final OpenGridFactorCandidates service;

    OpenGridFactorCandidatesResource(OpenGridFactorCandidates service){
        this.service=service;
    }

    @GET
    public Response board(@CookieParam(AccountAccess.COOKIE) String cookie){
        return secure(service.board(cookie)).build();
    }

    @GET
    @Path("/sources/{object}/rows")
    public Response rows(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("object") UUID object){
        return secure(service.rows(cookie,object)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@CookieParam(AccountAccess.COOKIE) String cookie,
                             OpenGridFactorCandidates.Register request){
        return secure(service.register(cookie,request)).status(201).build();
    }

    @POST
    @Path("/{candidate}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("candidate") UUID candidate,
                           OpenGridFactorCandidates.Review request){
        return secure(service.review(cookie,candidate,request)).status(201).build();
    }

    private static Response.ResponseBuilder secure(Object data){
        return Response.ok(data)
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
