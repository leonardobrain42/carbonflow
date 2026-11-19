package br.carbonflow.pilot;

import java.util.List;
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

/** Internal staff MFA: independent reviewer approves only internal comparability. */
@Path("/v1/platform/vehicle-ratings")
@Produces(MediaType.APPLICATION_JSON)
public class VehicleRatingResource {
    private final VehicleRatingService service;
    VehicleRatingResource(VehicleRatingService service){this.service=service;}

    @GET
    @Path("/board")
    public Response board(@CookieParam(AccountAccess.COOKIE) String token){
        return ok(service.board(token));
    }

    @GET
    @Path("/sources/{id}/candidates")
    public Response candidates(@CookieParam(AccountAccess.COOKIE) String token,
                               @PathParam("id") UUID id){
        return ok(service.candidates(token,id));
    }

    @POST
    @Path("/ratings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@CookieParam(AccountAccess.COOKIE) String token,
            @HeaderParam("Idempotency-Key") String key,VehicleRatingService.Register input){
        return Response.status(201).entity(service.register(token,key,input))
            .header("Cache-Control","private, no-store").build();
    }

    @POST
    @Path("/pairs")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response propose(@CookieParam(AccountAccess.COOKIE) String token,
            @HeaderParam("Idempotency-Key") String key,VehicleRatingService.Propose input){
        return Response.status(201).entity(service.propose(token,key,input))
            .header("Cache-Control","private, no-store").build();
    }

    @POST
    @Path("/pairs/{id}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("id") UUID id,@HeaderParam("If-Match") String version,
            VehicleRatingService.Review decision){
        var value=service.review(token,id,version,decision);
        return Response.ok(value).tag(Integer.toString(value.version()))
            .header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/fleets/{id}/composition")
    public Response composition(@CookieParam(AccountAccess.COOKIE) String token,
                                @PathParam("id") UUID id){
        return ok(service.composition(token,id));
    }

    @POST
    @Path("/fleets/{id}/snapshots")
    public Response freeze(@CookieParam(AccountAccess.COOKIE) String token,
            @HeaderParam("Idempotency-Key") String key,@PathParam("id") UUID id){
        return Response.status(201).entity(service.freeze(token,key,id))
            .header("Cache-Control","private, no-store").build();
    }

    private static Response ok(Object value){
        return Response.ok(value).header("Cache-Control","private, no-store").build();
    }
}
