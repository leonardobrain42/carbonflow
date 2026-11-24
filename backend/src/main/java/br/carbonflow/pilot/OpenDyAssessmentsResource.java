package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/open-dy-assessments")
@Produces(MediaType.APPLICATION_JSON)
public class OpenDyAssessmentsResource {
    private final OpenDyAssessments service;
    OpenDyAssessmentsResource(OpenDyAssessments service) { this.service=service; }

    @GET
    @Path("/options")
    public Response options(@CookieParam(AccountAccess.COOKIE) String cookie) {
        return secure(service.options(cookie)).build();
    }

    @POST
    @Path("/{project}/{assessment}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("assessment") java.util.UUID assessment,
                           @HeaderParam("Idempotency-Key") String key,@HeaderParam("If-Match") String match,
                           OpenDyAssessments.ReviewInput input) {
        var result=service.review(cookie,project,assessment,key,match,input);
        return secure(result.view()).status(result.created()?201:200)
            .tag(Integer.toString(result.view().version())).build();
    }

    @GET
    @Path("/{project}/{year}")
    public Response staffHistory(@CookieParam(AccountAccess.COOKIE) String cookie,
                                 @PathParam("project") String project,@PathParam("year") int year) {
        return secure(service.staffHistory(cookie,project,year)).build();
    }

    @POST
    @Path("/{project}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @HeaderParam("Idempotency-Key") String key,@HeaderParam("If-Match") String match,
                           OpenDyAssessments.Input input) {
        var result=service.record(cookie,project,key,match,input);
        return secure(result.view()).status(result.created()?201:200)
            .tag(Integer.toString(result.view().version())).build();
    }

    private static Response.ResponseBuilder secure(Object result) {
        return Response.ok(result).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}

@Path("/v1/projects/{project}/technical/open-dy-assessments")
@Produces(MediaType.APPLICATION_JSON)
class OpenDyAssessmentsProjectResource {
    private final OpenDyAssessments service;
    OpenDyAssessmentsProjectResource(OpenDyAssessments service) { this.service=service; }

    @GET
    @Path("/{year}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,@PathParam("year") int year) {
        return Response.ok(service.projectHistory(cookie,project,year))
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }
}
