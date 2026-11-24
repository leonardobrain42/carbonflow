package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/open-ir-assessments")
@Produces(MediaType.APPLICATION_JSON)
public class OpenIrAssessmentsResource {
    private final OpenIrAssessments service;
    OpenIrAssessmentsResource(OpenIrAssessments service) { this.service=service; }

    @POST
    @Path("/{project}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @HeaderParam("Idempotency-Key") String key,
                           @HeaderParam("If-Match") String match,
                           OpenIrAssessments.Input input) {
        var result=service.record(cookie,project,key,match,input);
        return secure(result.view()).status(result.created()?201:200)
            .tag(Integer.toString(result.view().version())).build();
    }

    @POST
    @Path("/{project}/{assessment}/review")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("assessment") java.util.UUID assessment,
                           @HeaderParam("Idempotency-Key") String key,
                           @HeaderParam("If-Match") String match,
                           OpenIrAssessments.ReviewInput input) {
        var result=service.review(cookie,project,assessment,key,match,input);
        return secure(result.view()).status(result.created()?201:200)
            .tag(Integer.toString(result.view().version())).build();
    }

    @GET
    @Path("/{project}/{year}/{sourceYear}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,@PathParam("year") int year,
                            @PathParam("sourceYear") int sourceYear) {
        return secure(service.staffHistory(cookie,project,year,sourceYear)).build();
    }

    static Response.ResponseBuilder secure(Object body) {
        return Response.ok(body).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}

@Path("/v1/projects/{project}/technical/open-ir-assessments")
@Produces(MediaType.APPLICATION_JSON)
class OpenIrAssessmentsProjectResource {
    private final OpenIrAssessments service;
    OpenIrAssessmentsProjectResource(OpenIrAssessments service) { this.service=service; }

    @GET
    @Path("/{year}/{sourceYear}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,@PathParam("year") int year,
                            @PathParam("sourceYear") int sourceYear) {
        return OpenIrAssessmentsResource.secure(service.history(cookie,project,year,sourceYear)).build();
    }
}
