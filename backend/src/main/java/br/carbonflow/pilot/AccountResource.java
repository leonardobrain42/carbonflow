package br.carbonflow.pilot;

import static br.carbonflow.pilot.AccountAccess.Purpose.RESET_PASSWORD;
import static br.carbonflow.pilot.AccountAccess.Purpose.VERIFY_EMAIL;

import io.vertx.ext.web.RoutingContext;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;

@Path("/v1/accounts")
@Consumes(MediaType.APPLICATION_JSON)
public class AccountResource {
    public record Registration(String email, String name) {}
    public record Address(String email) {}
    public record PasswordConfirmation(String token, String password) {}

    private final AccountAccess accounts;
    private final RoutingContext routing;

    AccountResource(AccountAccess accounts, RoutingContext routing) {
        this.accounts = accounts;
        this.routing = routing;
    }

    @POST
    public Response register(@HeaderParam("Idempotency-Key") String key, Registration request) {
        var registration = request == null ? new Registration(null, null) : request;
        accounts.register(registration.email(), registration.name(), key, peer());
        return Response.accepted().build();
    }

    @POST
    @Path("/email-verification")
    public Response emailVerification(@HeaderParam("Idempotency-Key") String key, Address request) {
        accounts.requestChallenge(VERIFY_EMAIL, request == null ? null : request.email(), key, peer());
        return Response.accepted().build();
    }

    @POST
    @Path("/email-confirmations")
    public Response emailConfirmation(@HeaderParam("Idempotency-Key") String key, PasswordConfirmation request) {
        var confirmation = request == null ? new PasswordConfirmation(null, null) : request;
        accounts.complete(VERIFY_EMAIL, confirmation.token(), confirmation.password(), key, peer());
        return Response.noContent().build();
    }

    @POST
    @Path("/password-recovery")
    public Response recoverPassword(@HeaderParam("Idempotency-Key") String key, Address request) {
        accounts.requestChallenge(RESET_PASSWORD, request == null ? null : request.email(), key, peer());
        return Response.accepted().build();
    }

    @POST
    @Path("/password-resets")
    public Response resetPassword(@HeaderParam("Idempotency-Key") String key, PasswordConfirmation request) {
        var reset = request == null ? new PasswordConfirmation(null, null) : request;
        accounts.complete(RESET_PASSWORD, reset.token(), reset.password(), key, peer());
        return Response.noContent().build();
    }

    private String peer() {
        return routing.request().remoteAddress().hostAddress();
    }
}

@Path("/v1/account-session")
@Produces(MediaType.APPLICATION_JSON)
class AccountSessionResource {
    public record Credentials(String email, String password) {}

    private final AccountAccess accounts;
    private final RoutingContext routing;

    AccountSessionResource(AccountAccess accounts, RoutingContext routing) {
        this.accounts = accounts;
        this.routing = routing;
    }

    @GET
    public AccountAccess.Session current(@CookieParam(AccountAccess.COOKIE) String token) {
        return accounts.session(token);
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(@CookieParam(AccountAccess.COOKIE) String previous, @Context UriInfo uri, Credentials request) {
        var credentials = request == null ? new Credentials(null, null) : request;
        String token = accounts.login(credentials.email(), credentials.password(), previous,
                routing.request().remoteAddress().hostAddress());
        return Response.noContent().header("Set-Cookie",
                PilotSessionResource.cookie(AccountAccess.COOKIE, token, AccountAccess.SESSION_SECONDS, uri)).build();
    }

    @DELETE
    public Response logout(@CookieParam(AccountAccess.COOKIE) String token, @Context UriInfo uri) {
        accounts.logout(token);
        return Response.noContent().header("Set-Cookie",
                PilotSessionResource.cookie(AccountAccess.COOKIE, "", 0, uri)).build();
    }
}
