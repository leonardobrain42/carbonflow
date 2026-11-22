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
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/**
 * Batch admission inside a frozen period snapshot. The response never
 * authorizes an E8 run.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/period-qa/snapshots/{snapshot}/batch-admissions")
@Produces(MediaType.APPLICATION_JSON)
public class OpenPeriodBatchAdmissionsResource {
    private final OpenPeriodBatchAdmissions admissions;

    OpenPeriodBatchAdmissionsResource(OpenPeriodBatchAdmissions admissions) {
        this.admissions = admissions;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("snapshot") UUID snapshot) {
        return noStore(admissions.list(cookie, project, snapshot)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("snapshot") UUID snapshot,
                           @HeaderParam("Idempotency-Key") String key,
                           @HeaderParam("If-Match") String match,
                           OpenPeriodBatchAdmissions.Decision decision) {
        var result = admissions.decide(cookie, project, snapshot, key, match, decision);
        return noStore(result.view()).status(result.created() ? 201 : 200)
            .tag(Integer.toString(result.view().version())).build();
    }

    private static Response.ResponseBuilder noStore(Object payload) {
        return Response.ok(payload)
            .header("Cache-Control", "private, no-store")
            .header("X-Content-Type-Options", "nosniff");
    }
}
