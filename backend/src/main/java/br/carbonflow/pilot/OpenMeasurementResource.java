package br.carbonflow.pilot;

import java.util.List;
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

/**
 * CSV-to-candidate ingestion only. This API cannot approve readings, close a
 * monitoring vintage, calculate emissions or issue credits.
 */
@Path("/v1/projects/{project}/technical/open-monitoring/batches")
@Produces(MediaType.APPLICATION_JSON)
public class OpenMeasurementResource {
    private final OpenMeasurementIngestion ingestion;

    OpenMeasurementResource(OpenMeasurementIngestion ingestion) {
        this.ingestion=ingestion;
    }

    @POST
    @Consumes("text/csv")
    public Response ingest(@CookieParam(AccountAccess.COOKIE) String cookie,
          @PathParam("project") String project,
          @HeaderParam("X-Source-Id") String source,
          @HeaderParam("X-File-Name") String name,
          @HeaderParam("Idempotency-Key") String key,
          byte[] csv) {
        var result=ingestion.submit(cookie,project,source,name,key,csv);
        return Response.status(Response.Status.ACCEPTED).entity(result)
            .header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff").build();
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project) {
        return Response.ok(ingestion.list(cookie,project))
            .header("Cache-Control","private, no-store").build();
    }

    @GET
    @Path("/{batchId}")
    public Response read(@CookieParam(AccountAccess.COOKIE) String cookie,
                         @PathParam("project") String project,
                         @PathParam("batchId") UUID batch) {
        return Response.ok(ingestion.read(cookie,project,batch))
            .header("Cache-Control","private, no-store").build();
    }
}
