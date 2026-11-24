package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1")
@Produces(MediaType.APPLICATION_JSON)
public class OpenDyAssessmentsResource {
    private final OpenDyAssessments service;
    OpenDyAssessmentsResource(OpenDyAssessments service) { this.service=service; }

    @GET
    @Path("/platform/open-dy-assessments/{project}/{year}")
    public Response staffHistory(@CookieParam(AccountAccess.COOKIE) String cookie,
                                 @PathParam("project") String project,@PathParam("year") int year) {
        return secure(service.staffHistory(cookie,project,year)).build();
    }

    @POST
    @Path("/platform/open-dy-assessments/{project}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @HeaderParam("Idempotency-Key") String key,@HeaderParam("If-Match") String match,
                           OpenDyAssessments.Input input) {
        var result=service.record(cookie,project,key,match,input);
        return secure(result.view()).status(result.created()?201:200)
            .tag(Integer.toString(result.view().version())).build();
    }

    @GET
    @Path("/projects/{project}/technical/open-dy-assessments/{year}")
    public Response projectHistory(@CookieParam(AccountAccess.COOKIE) String cookie,
                                   @PathParam("project") String project,@PathParam("year") int year) {
        return secure(service.projectHistory(cookie,project,year)).build();
    }

    private static Response.ResponseBuilder secure(Object result) {
        return Response.ok(result).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
