package br.carbonflow.pilot;

import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/compliance")
@Produces(MediaType.APPLICATION_JSON)
public class ComplianceResource {
    public record Verification(String code) {}

    private final ComplianceStaff staff;
    private final ComplianceQueue queue;

    private final ComplianceDecisions decisions;

    ComplianceResource(ComplianceStaff staff, ComplianceQueue queue, ComplianceDecisions decisions) {
        this.staff = staff;
        this.queue = queue;
        this.decisions = decisions;
    }

    @GET
    @Path("/session")
    public Response session(@CookieParam(AccountAccess.COOKIE) String token) {
        return privateJson(staff.status(token));
    }

    @POST
    @Path("/session")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response verify(@CookieParam(AccountAccess.COOKIE) String token, Verification verification) {
        return privateJson(staff.verify(token, verification == null ? null : verification.code()));
    }

    @GET
    @Path("/queue")
    public Response queue(@CookieParam(AccountAccess.COOKIE) String token, @QueryParam("offset") @DefaultValue("0") int offset) {
        return privateJson(queue.queue(token, offset));
    }

    @GET
    @Path("/projects/{project}/requests/{request}/documents")
    public Response documents(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                              @PathParam("request") UUID request) {
        return privateJson(queue.documents(token, project, request));
    }

    @GET
    @Path("/projects/{project}/requests/{request}/documents/{document}")
    @Produces(MediaType.APPLICATION_OCTET_STREAM)
    public Response download(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                             @PathParam("request") UUID request, @PathParam("document") UUID document) {
        return attachment(queue.download(token, project, request, document), document);
    }

    @GET
    @Path("/projects/{project}/requests/{request}/decisions")
    public Response history(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                            @PathParam("request") UUID request) {
        return privateJson(decisions.history(token, project, request));
    }

    @POST
    @Path("/projects/{project}/requests/{request}/decisions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("request") UUID request, @HeaderParam("If-Match") String version,
                           @HeaderParam("Idempotency-Key") String key, ComplianceDecisions.Input input) {
        return privateJson(decisions.decide(token, project, request, version, key, input));
    }

    static Response attachment(byte[] content, UUID id) {
        return Response.ok(content, MediaType.APPLICATION_OCTET_STREAM)
                .header("Content-Disposition", "attachment; filename=\"comprovante-" + id + ".pdf\"")
                .header("Cache-Control", "private, no-store")
                .header("X-Content-Type-Options", "nosniff").build();
    }

    static Response privateJson(Object body) {
        return Response.ok(body).header("Cache-Control", "private, no-store").build();
    }
}
