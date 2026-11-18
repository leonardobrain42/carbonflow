package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.DefaultValue;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/sections/{section}/assessment")
@Produces(MediaType.APPLICATION_JSON)
public class SectionAssessmentResource {
    private final SectionAssessment assessment;

    SectionAssessmentResource(SectionAssessment assessment) {
        this.assessment = assessment;
    }

    @GET
    public Response get(@CookieParam(AccountAccess.COOKIE) String cookie, @PathParam("project") String project,
                        @PathParam("section") String section, @QueryParam("act") @DefaultValue("PD") RequirementResolver.Act act) {
        return Response.ok(assessment.get(cookie,project,section,act)).header("Cache-Control","private, no-store").build();
    }
}
