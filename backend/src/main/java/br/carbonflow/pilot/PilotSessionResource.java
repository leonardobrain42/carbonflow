package br.carbonflow.pilot;


import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerRequestFilter;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import jakarta.ws.rs.core.UriInfo;
import jakarta.ws.rs.ext.Provider;

@Path("/v1/session")
@Produces(MediaType.APPLICATION_JSON)
public class PilotSessionResource {
    public record Credentials(String identity, String password) {}

    private final PilotAccess access;

    PilotSessionResource(PilotAccess access) {
        this.access = access;
    }

    @GET
    public PilotAccess.Session current() {
        return access.session();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response login(Credentials credentials, @Context UriInfo uri) {
        String token = access.login(credentials == null ? null : credentials.identity(),
                credentials == null ? null : credentials.password());
        return Response.noContent().header("Set-Cookie", cookie(token, PilotAccess.SESSION_SECONDS, uri)).build();
    }

    @DELETE
    public Response logout(@Context UriInfo uri) {
        access.logout();
        return Response.noContent().header("Set-Cookie", cookie("", 0, uri)).build();
    }

    private String cookie(String token, int age, UriInfo uri) {
        String secure = "https".equals(uri.getBaseUri().getScheme()) ? "; Secure" : "";
        return PilotAccess.COOKIE + "=" + token + "; Path=/; Max-Age=" + age + "; HttpOnly; SameSite=Strict" + secure;
    }
}

@Path("/health/ready")
class PilotReadinessResource {
    private final PilotDatabase database;

    PilotReadinessResource(PilotDatabase database) {
        this.database = database;
    }

    @GET
    public Response ready() {
        return database.read(connection -> connection.isValid(1))
                ? Response.noContent().build() : Response.status(503).build();
    }
}

@Provider
class PilotOriginFilter implements ContainerRequestFilter, ContainerResponseFilter {
    @Override
    public void filter(ContainerRequestContext request) {
        String path = request.getUriInfo().getRequestUri().getPath();
        if (!path.startsWith("/v1/") || "GET".equals(request.getMethod()) || "HEAD".equals(request.getMethod())) {
            return;
        }
        String origin = request.getHeaderString("Origin");
        var base = request.getUriInfo().getBaseUri();
        String expected = base.getScheme() + "://" + base.getAuthority();
        if (origin != null && !expected.equalsIgnoreCase(origin)) {
            request.abortWith(Response.status(403).type(MediaType.APPLICATION_JSON_TYPE)
                    .entity(java.util.Map.of("status", 403, "code", "ORIGEM_NEGADA",
                            "message", "Ação disponível na origem da aplicação.")).build());
        }
    }

    @Override
    public void filter(ContainerRequestContext request, ContainerResponseContext response) {
        if (request.getUriInfo().getRequestUri().getPath().startsWith("/v1/")) {
            response.getHeaders().putSingle("Cache-Control", "no-store");
            response.getHeaders().putSingle("X-Content-Type-Options", "nosniff");
        }
    }
}
