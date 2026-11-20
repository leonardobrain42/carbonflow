package br.carbonflow.pilot;

import java.util.List;
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

/**
 * VM0038 v1.1 independent INTERNAL assessment, not an official VVB/Verra
 * validation and not a calculation or authorization to issue VCUs.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/method-review")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMethodReviewResource {
    private final OpenMethodReview service;

    OpenMethodReviewResource(OpenMethodReview service) {
        this.service=service;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String cookie,
                        @PathParam("project") String project,
                        @PathParam("period") String period) {
        return secure(service.get(cookie,project,period)).build();
    }

    @GET
    @Path("/versions/{dossierVersion}")
    public Response history(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,
                            @PathParam("period") String period,
                            @PathParam("dossierVersion") int dossierVersion) {
        return secure(service.history(cookie,project,period,dossierVersion)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenMethodReview.Decision decision) {
        var view=service.decide(cookie,project,period,match,key,decision);
        return secure(view).tag(Integer.toString(view.review().version())).build();
    }

    private static Response.ResponseBuilder secure(Object entity) {
        return Response.ok(entity).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
