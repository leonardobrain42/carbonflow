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
    private final FleetCatalogApplication application;

    FleetAliasCandidatesResource(FleetAliasCandidates candidates, FleetCatalogApplication application) {
        this.application = application;
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
    @Path("/dry-run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response dryRunImport(@CookieParam(AccountAccess.COOKIE) String token, FleetAliasCandidates.ImportInput input) {
        return Response.ok(candidates.dryRunImport(token, input)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/decisions/dry-run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response dryRunDecisions(@CookieParam(AccountAccess.COOKIE) String token, FleetAliasCandidates.BulkInput input) {
        return Response.ok(candidates.bulkDecide(token, input, false)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/decisions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decideInBulk(@CookieParam(AccountAccess.COOKIE) String token, FleetAliasCandidates.BulkInput input) {
        var result = candidates.bulkDecide(token, input, true);
        return Response.status(result.applied() ? 201 : 422).entity(result).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/applications/dry-run")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response dryRunApplications(@CookieParam(AccountAccess.COOKIE) String token, FleetCatalogApplication.Input input) {
        return Response.ok(application.run(token, input, false)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/applications")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response applyToCatalog(@CookieParam(AccountAccess.COOKIE) String token, FleetCatalogApplication.Input input) {
        var result = application.run(token, input, true);
        return Response.status(result.applied() ? 201 : 422).entity(result).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/{id}/decision")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("id") UUID id,
                           FleetAliasCandidates.DecisionInput input) {
        return Response.ok(candidates.decide(token, id, input)).header("Cache-Control", "private, no-store").build();
    }
}
