package br.carbonflow.pilot;

import java.time.OffsetDateTime;
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
 * Internal QA: candidate review and period reconciliation. No E8 binding,
 * Verra approval, VVB status or issuance routes are exposed.
 */
@Path("/v1/projects/{project}/technical/open-monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMeasurementReviewResource {
    private final OpenMeasurementReviewQa qa;
    OpenMeasurementReviewResource(OpenMeasurementReviewQa qa) {
        this.qa=qa;
    }

    @GET
    @Path("/batches/{batchId}/reviews")
    public Response reviews(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,
                            @PathParam("batchId") UUID batch) {
        return noStore(qa.reviews(cookie,project,batch)).build();
    }

    @POST
    @Path("/batches/{batchId}/rows/{line}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("batchId") UUID batch,
                           @PathParam("line") int line,
                           @HeaderParam("If-Match") String match,
                           OpenMeasurementReviewQa.Decision decision) {
        var result=qa.review(cookie,project,batch,line,match,decision);
        return noStore(result).tag(Integer.toString(result.version())).build();
    }

    @GET
    @Path("/period-qa")
    public Response period(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @QueryParam("from") OffsetDateTime from,
                           @QueryParam("until") OffsetDateTime until) {
        return noStore(qa.preview(cookie,project,from,until)).build();
    }

    @POST
    @Path("/period-qa/snapshots")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response snapshot(@CookieParam(AccountAccess.COOKIE) String cookie,
                             @PathParam("project") String project,
                             PeriodInput period) {
        if(period==null)throw PilotProblem.invalid("PERIODO_QA_INVALIDO",
            "period","CF-OPEN-009","Informe início e fim do período.");
        return Response.status(201).entity(qa.freeze(cookie,project,period.from(),period.until()))
            .header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/period-qa/snapshots")
    public Response snapshots(@CookieParam(AccountAccess.COOKIE) String cookie,
                              @PathParam("project") String project) {
        return noStore(qa.snapshots(cookie,project)).build();
    }

    record PeriodInput(OffsetDateTime from,OffsetDateTime until) {}

    private static Response.ResponseBuilder noStore(Object result) {
        return Response.ok(result).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
