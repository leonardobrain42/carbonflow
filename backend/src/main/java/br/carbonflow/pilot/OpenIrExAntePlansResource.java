package br.carbonflow.pilot;

import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/projects/{project}/technical/open-ir-ex-ante-plan")
@Produces(MediaType.APPLICATION_JSON)
public class OpenIrExAntePlansResource {
    private final OpenIrExAntePlans plans;
    OpenIrExAntePlansResource(OpenIrExAntePlans plans) {this.plans=plans;}

    @GET
    public Response read(@CookieParam(AccountAccess.COOKIE) String cookie,@PathParam("project") String project) {
        var view=plans.read(cookie,project);
        return OpenIrAssessmentsResource.secure(view).tag(Integer.toString(view.latestVersion())).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@CookieParam(AccountAccess.COOKIE) String cookie,@PathParam("project") String project,
                         @HeaderParam("Idempotency-Key") String key,@HeaderParam("If-Match") String match,
                         OpenIrExAntePlans.Input input) {
        var result=plans.save(cookie,project,key,match,input);
        return OpenIrAssessmentsResource.secure(result.revision()).status(result.created()?201:200)
            .tag(Integer.toString(result.revision().version())).build();
    }
}
