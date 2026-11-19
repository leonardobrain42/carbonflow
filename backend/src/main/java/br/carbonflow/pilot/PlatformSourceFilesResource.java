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

/** No endpoint exposes the original bytes; all processing rechecks current rights. */
@Path("/v1/platform/source-files")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformSourceFilesResource {
    private final PlatformSourceFiles files;

    PlatformSourceFilesResource(PlatformSourceFiles files) {this.files=files;}

    @GET
    @Path("/submissions/{submissionId}")
    public Response list(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("submissionId") UUID id) {
        return noStore(files.list(token,id));
    }

    @POST
    @Path("/submissions/{submissionId}")
    @Consumes(MediaType.APPLICATION_OCTET_STREAM)
    public Response upload(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("submissionId") UUID id,
            @HeaderParam("X-CarbonFlow-File-Request") String intent,
            @HeaderParam("X-Source-Filename") String filename,
            @HeaderParam("X-Source-Format") String format,
            byte[] bytes) {
        if(!"1".equals(intent))throw PilotProblem.invalid("ORIGEM_INVALIDA","upload",
                "CF-OPEN-003","Envio protegido exige intenção explícita.");
        return Response.status(201).entity(files.upload(token,id,filename,format,bytes))
            .header("Cache-Control","private, no-store").build();
    }

    @POST
    @Path("/objects/{objectId}/parse")
    public Response parse(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("objectId") UUID id) {
        return noStore(files.parse(token,id));
    }

    @POST
    @Path("/objects/{objectId}/assess")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response assess(@CookieParam(AccountAccess.COOKIE) String token,
            @PathParam("objectId") UUID id, PlatformSourceFiles.Decision assessment) {
        return noStore(files.assess(token,id,assessment));
    }

    private static Response noStore(Object value){
        return Response.ok(value).header("Cache-Control","private, no-store").build();
    }
}
