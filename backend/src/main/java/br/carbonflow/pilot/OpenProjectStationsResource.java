package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Versioned station metadata and project boundary, no automatic normative approval. */
@Path("/v1/projects/{project}/technical/open-sites")
@Produces(MediaType.APPLICATION_JSON)
public class OpenProjectStationsResource {
    private final OpenProjectStations sites;

    OpenProjectStationsResource(OpenProjectStations sites){this.sites=sites;}

    @GET public Response get(@CookieParam(AccountAccess.COOKIE) String token,
                              @PathParam("project") String project){
        return Response.ok(sites.get(token,project)).header("Cache-Control","private, no-store").build();
    }

    @PUT @Path("/sites/{site}") @Consumes(MediaType.APPLICATION_JSON)
    public Response saveSite(@CookieParam(AccountAccess.COOKIE) String token,
        @PathParam("project") String project,@PathParam("site") String site,
        @HeaderParam("If-Match") String version,@HeaderParam("Idempotency-Key") String key,
        OpenProjectStations.SiteInput input){
        var result=sites.saveSite(token,project,site,version,key,input);
        return Response.ok(result).tag(Integer.toString(result.version()))
            .header("Cache-Control","private, no-store").build();
    }

    @PUT @Path("/boundary") @Consumes(MediaType.APPLICATION_JSON)
    public Response saveBoundary(@CookieParam(AccountAccess.COOKIE) String token,
        @PathParam("project") String project,
        @HeaderParam("If-Match") String version,@HeaderParam("Idempotency-Key") String key,
        OpenProjectStations.BoundaryInput input){
        var result=sites.saveBoundary(token,project,version,key,input);
        return Response.ok(result).tag(Integer.toString(result.version()))
            .header("Cache-Control","private, no-store").build();
    }
}
