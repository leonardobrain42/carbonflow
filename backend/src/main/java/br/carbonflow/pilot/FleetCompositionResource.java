package br.carbonflow.pilot;

import java.util.UUID;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Internal staff+MFA endpoints. No generic public vehicle data or E8 write endpoint.
 */
@Path("/v1/platform/fleet")
@Produces(MediaType.APPLICATION_JSON)
public class FleetCompositionResource {
    private final FleetCompositionService service;

    FleetCompositionResource(FleetCompositionService service){this.service=service;}

    @GET
    @Path("/board")
    public Response board(@CookieParam(AccountAccess.COOKIE) String token){
        return ok(service.board(token));
    }

    @POST
    @Path("/models")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response register(@CookieParam(AccountAccess.COOKIE) String token,
                             @HeaderParam("Idempotency-Key") String commandKey,
                             FleetCompositionService.Registration input){
        return Response.status(201).entity(service.register(token,commandKey,input))
            .header("Cache-Control","private, no-store").build();
    }

    @POST
    @Path("/models/{modelId}/aliases")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response alias(@CookieParam(AccountAccess.COOKIE) String token,
                          @PathParam("modelId") UUID model,
                          @HeaderParam("Idempotency-Key") String commandKey,
                          FleetCompositionService.AliasInput input){
        return ok(service.alias(token,commandKey,model,input));
    }

    @GET
    @Path("/sources/{sourceObjectId}/preview")
    public Response preview(@CookieParam(AccountAccess.COOKIE) String token,
                            @PathParam("sourceObjectId") UUID source,
                            @QueryParam("year") int year){
        return ok(service.preview(token,source,year));
    }

    @POST
    @Path("/imports")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@CookieParam(AccountAccess.COOKIE) String token,
                           @HeaderParam("Idempotency-Key") String commandKey,
                           FleetCompositionService.Create request){
        return Response.status(201).entity(service.create(token,commandKey,request))
            .header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/imports/{id}")
    public Response get(@CookieParam(AccountAccess.COOKIE) String token,
                        @PathParam("id") UUID id){
        return ok(service.get(token,id));
    }

    @POST
    @Path("/imports/{id}/coverage")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response coverage(@CookieParam(AccountAccess.COOKIE) String token,
                             @PathParam("id") UUID id,
                             @HeaderParam("Idempotency-Key") String commandKey,
                             @HeaderParam("If-Match") String version,
                             FleetCompositionService.CoverageInput input){
        var result=service.decide(token,commandKey,version,id,input);
        return Response.status(result.created()?201:200).entity(result.detail())
            .header("Cache-Control","private, no-store")
            .tag(Integer.toString(result.detail().coverageDecision().version())).build();
    }

    private static Response ok(Object value){
        return Response.ok(value).header("Cache-Control","private, no-store").build();
    }
}
