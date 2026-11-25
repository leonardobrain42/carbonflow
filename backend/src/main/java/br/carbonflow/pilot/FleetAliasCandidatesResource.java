package br.carbonflow.pilot;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import java.util.UUID;

/** Platform administration only (staff, MFA, rosters). Candidates are review evidence, never catalog entries. */
@Path("/v1/platform/fleet/alias-candidates")
@Produces(MediaType.APPLICATION_JSON)
public class FleetAliasCandidatesResource {
    private final FleetAliasCandidates candidates;

    FleetAliasCandidatesResource(FleetAliasCandidates candidates) {
        this.candidates = candidates;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token, @QueryParam("state") String state) {
        return Response.ok(candidates.list(token, state)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response importBatch(@CookieParam(AccountAccess.COOKIE) String token, FleetAliasCandidates.ImportInput input) {
        return Response.ok(candidates.importBatch(token, input)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/{id}/decision")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           FleetAliasCandidates.DecisionInput input) {
        return Response.ok(candidates.decide(token, id, input)).header("Cache-Control", "private, no-store").build();
    }
}
