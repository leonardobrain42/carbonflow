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

/** Not an OM implementation, normative admission, or an E8 computation. */
@Path("/v1/projects/{project}/technical/open-monitoring/dossiers/{period}/efel-candidates/{efel}/vt0011-workbooks")
@Produces(MediaType.APPLICATION_JSON)
public class OpenVt0011WorkbooksResource {
    private final OpenVt0011Workbooks workbooks;
    OpenVt0011WorkbooksResource(OpenVt0011Workbooks workbooks){this.workbooks=workbooks;}

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("period") String period,
                         @PathParam("efel") UUID efel) {
        return secure(workbooks.list(cookie,project,period,efel)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @PathParam("efel") UUID efel,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenVt0011Workbooks.Input input) {
        if(input==null || !efel.equals(input.efelCandidateId()))
            throw new PilotProblem(400,"VT0011_SOURCE_MISMATCH","efelCandidateId",
                "CF-OPEN-007","Série EFel da URL diferente da informada no caderno.");
        var result=workbooks.submit(cookie,project,period,match,key,input);
        return secure(result).status(201).tag(Integer.toString(result.version())).build();
    }

    @POST
    @Path("/{workbook}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("period") String period,
                           @PathParam("efel") UUID efel,
                           @PathParam("workbook") UUID workbook,
                           @HeaderParam("If-Match") String match,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenVt0011Workbooks.Decision decision) {
        var list=workbooks.list(cookie,project,period,efel);
        if(list.stream().noneMatch(w->w.id().equals(workbook)))
            throw new PilotProblem(404,"VT0011_WORKBOOK_NOT_VISIBLE","workbook",
                "CF-OPEN-007","Caderno indisponível.");
        var result=workbooks.review(cookie,project,period,workbook,match,key,decision);
        return secure(result).tag(Integer.toString(result.review().version())).build();
    }

    private static Response.ResponseBuilder secure(Object entity) {
        return Response.ok(entity).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
