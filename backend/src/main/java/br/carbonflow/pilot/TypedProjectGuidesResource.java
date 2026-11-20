package br.carbonflow.pilot;

import java.util.Map;
import com.fasterxml.jackson.databind.JsonNode;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Opt-in typed answers: parallel to V26 historical text. No old revisions are coerced.
 * Each operation uses the same guide ACL as the legacy API.
 */
@Path("/v1/projects/{project}/technical/typed-guides")
@Produces(MediaType.APPLICATION_JSON)
public class TypedProjectGuidesResource {
    private final TypedProjectGuides service;
    TypedProjectGuidesResource(TypedProjectGuides service){this.service=service;}

    @GET public Response catalog(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project){
        return Response.ok(service.get(cookie,project))
            .header("Cache-Control","private, no-store").build();
    }
    @GET @Path("/{guide}/revisions/{version}")
    public Response revision(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("guide") String guide,
        @PathParam("version") int version){
        return Response.ok(service.revision(cookie,project,guide,version))
            .header("Cache-Control","private, no-store").build();
    }
    @PUT @Path("/{guide}") @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("guide") String guide,
        @HeaderParam("If-Match") String match,
        @HeaderParam("X-Guide-Definition-Version") String definition,
        @HeaderParam("Idempotency-Key") String key,Map<String,JsonNode> answers){
        var result=service.save(cookie,project,guide,match,definition,key,answers);
        return Response.ok(result).tag(Integer.toString(result.version()))
            .header("Cache-Control","private, no-store").build();
    }
}
