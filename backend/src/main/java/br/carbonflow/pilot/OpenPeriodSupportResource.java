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
 * Project-private documentary due diligence; never returns original file bytes,
 * external certification, admissible E8 inputs or VCU calculations.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/period-qa")
@Produces(MediaType.APPLICATION_JSON)
public class OpenPeriodSupportResource {
    private final OpenPeriodSupport support;
    OpenPeriodSupportResource(OpenPeriodSupport support) { this.support=support; }

    @GET
    @Path("/snapshots/{snapshot}/admissibility")
    public Response readiness(@CookieParam(AccountAccess.COOKIE) String cookie,
                              @PathParam("project") String project,
                              @PathParam("snapshot") UUID snapshot) {
        return noStore(support.readiness(cookie,project,snapshot)).build();
    }

    @POST
    @Path("/snapshots/{snapshot}/support")
    @Consumes("application/pdf")
    public Response submit(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("snapshot") UUID snapshot,
                           @HeaderParam("X-Evidence-Kind") String kind,
                           @HeaderParam("X-Subject-Code") String subject,
                           @HeaderParam("X-Evidence-Reference") String reference,
                           @HeaderParam("X-File-Name") String filename,
                           @HeaderParam("Idempotency-Key") String key,
                           byte[] pdf) {
        return noStore(support.submit(cookie,project,snapshot,kind,subject,
            reference,filename,key,pdf)).status(201).build();
    }

    @POST
    @Path("/support/{document}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("document") UUID document,
                           @HeaderParam("If-Match") String match,
                           OpenPeriodSupport.Decision decision) {
        var result=support.review(cookie,project,document,match,decision);
        return noStore(result).tag(Integer.toString(result.version())).build();
    }

    private static Response.ResponseBuilder noStore(Object payload) {
        return Response.ok(payload).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
