package br.carbonflow.pilot;

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
import java.util.UUID;

/** Platform administration only (staff, MFA, licence rosters). Declared authorisations, never document content. */
@Path("/v1/platform/source-licenses")
@Produces(MediaType.APPLICATION_JSON)
public class ContentLicensesResource {
    private final ContentLicenses licenses;

    ContentLicensesResource(ContentLicenses licenses) {
        this.licenses = licenses;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token) {
        return Response.ok(licenses.list(token)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String token, ContentLicenses.SubmitInput input) {
        var saved = licenses.submit(token, input);
        return Response.status(201).entity(saved).header("ETag", "\"" + saved.decisionVersion() + "\"")
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/public")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response declarePublic(@CookieParam(AccountAccess.COOKIE) String token, ContentLicenses.DeclarePublicInput input) {
        var saved = licenses.declarePublic(token, input);
        return Response.status(201).entity(saved).header("ETag", "\"" + saved.decisionVersion() + "\"")
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/{id}/decision")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           @HeaderParam("If-Match") String match, ContentLicenses.DecisionInput input) {
        var saved = licenses.decide(token, id, match, input);
        return Response.ok(saved).header("ETag", "\"" + saved.decisionVersion() + "\"")
                .header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/{id}/revoke")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response revoke(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           @HeaderParam("If-Match") String match, ContentLicenses.RevokeInput input) {
        var saved = licenses.revoke(token, id, match, input);
        return Response.ok(saved).header("ETag", "\"" + saved.decisionVersion() + "\"")
                .header("Cache-Control", "private, no-store").build();
    }
}
