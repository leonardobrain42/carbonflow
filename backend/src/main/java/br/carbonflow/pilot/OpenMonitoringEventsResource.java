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

/** Operational incident ledger, not E8 admission or external verification. */
@Path("/v1/projects/{project}/technical/open-monitoring/events")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMonitoringEventsResource {
    private final OpenMonitoringEvents events;
    OpenMonitoringEventsResource(OpenMonitoringEvents events) {
        this.events=events;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project) {
        return noStore(events.list(cookie,project)).build();
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response report(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @HeaderParam("Idempotency-Key") String key,
                           OpenMonitoringEvents.Input payload) {
        return noStore(events.report(cookie,project,key,payload)).status(201).build();
    }

    @POST
    @Path("/{event}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
                           @PathParam("project") String project,
                           @PathParam("event") UUID event,
                           @HeaderParam("If-Match") String match,
                           OpenMonitoringEvents.Decision decision) {
        var answer=events.review(cookie,project,event,match,decision);
        return noStore(answer).tag(Integer.toString(answer.version())).build();
    }

    private static Response.ResponseBuilder noStore(Object value) {
        return Response.ok(value).header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
