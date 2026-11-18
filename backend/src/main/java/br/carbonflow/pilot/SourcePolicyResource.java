package br.carbonflow.pilot;

import java.util.List;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/source-policy")
@Produces(MediaType.APPLICATION_JSON)
public class SourcePolicyResource {
    record View(boolean normativeAiDisabled, List<ContentLicensePolicy.Metadata> sources) {}

    private final DynamicProjectScope scopes;
    private final ContentLicensePolicy policy;

    SourcePolicyResource(DynamicProjectScope scopes, ContentLicensePolicy policy) {
        this.scopes = scopes;
        this.policy = policy;
    }

    @GET
    public Response status(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project) {
        View status = scopes.read(token, project, "VIEW_GUIDES", (connection, scope) ->
                new View(policy.normativeAiDisabled(), policy.metadata(connection, scope,
                        ProjectJourney.profile(ProjectGuides.candidate(connection, project)))));
        return Response.ok(status).header("Cache-Control", "private, no-store").build();
    }
}
