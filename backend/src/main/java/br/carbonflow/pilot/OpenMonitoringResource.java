package br.carbonflow.pilot;

import static br.carbonflow.pilot.OpenMonitoringModel.*;

import java.time.OffsetDateTime;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Preparatory configuration, not a measurement import or VCS certification. */
@Path("/v1/projects/{project}/technical/open-monitoring")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMonitoringResource {
    private final OpenMonitoringConfiguration monitoring;

    OpenMonitoringResource(OpenMonitoringConfiguration monitoring) {
        this.monitoring = monitoring;
    }

    @GET
    public Response view(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @QueryParam("from") OffsetDateTime from,
                         @QueryParam("until") OffsetDateTime until) {
        return Response.ok(monitoring.get(cookie,project,from,until))
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }

    @PUT
    @Path("/chargers/{charger}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response charger(@CookieParam(AccountAccess.COOKIE) String cookie,
                            @PathParam("project") String project,
                            @PathParam("charger") String charger,
                            @HeaderParam("If-Match") String ifMatch,
                            @HeaderParam("Idempotency-Key") String key,
                            ChargerInput input) {
        var value=monitoring.saveCharger(cookie,project,charger,ifMatch,key,input);
        return Response.ok(value).tag(Integer.toString(value.version()))
            .header("Cache-Control","private, no-store").build();
    }

    @PUT
    @Path("/meters/{meter}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response meter(@CookieParam(AccountAccess.COOKIE) String cookie,
                          @PathParam("project") String project,
                          @PathParam("meter") String meter,
                          @HeaderParam("If-Match") String ifMatch,
                          @HeaderParam("Idempotency-Key") String key,
                          MeterInput input) {
        var value=monitoring.saveMeter(cookie,project,meter,ifMatch,key,input);
        return Response.ok(value).tag(Integer.toString(value.version()))
            .header("Cache-Control","private, no-store").build();
    }
}
