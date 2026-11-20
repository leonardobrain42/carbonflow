package br.carbonflow.pilot;

import java.util.UUID;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

/** Project-private documentary provenance; no source PDF bytes exposed publicly. */
@Path("/v1/projects/{project}/technical/open-sites/evidence")
@Produces(MediaType.APPLICATION_JSON)
public class OpenAssetEvidenceResource {
    private final OpenAssetEvidence evidence;
    OpenAssetEvidenceResource(OpenAssetEvidence evidence){this.evidence=evidence;}

    @GET @Path("/{type}/{code}")
    public Response list(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("type") String type,
        @PathParam("code") String code){
        return secure(Response.ok(evidence.list(cookie,project,type,code))).build();
    }
    @POST @Path("/{type}/{code}/revisions/{version}")
    @Consumes("application/pdf")
    public Response upload(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("type") String type,
        @PathParam("code") String code,@PathParam("version") int version,
        @HeaderParam("X-Evidence-Purpose") String purpose,
        @HeaderParam("X-Evidence-Reference") String reference,
        @HeaderParam("X-Evidence-Locator") String locator,
        @HeaderParam("X-File-Name") String filename,
        @HeaderParam("Idempotency-Key") String key,byte[] pdf){
        return secure(Response.status(201).entity(evidence.upload(cookie,project,type,code,
            version,purpose,reference,locator,filename,key,pdf))).build();
    }
    @GET @Path("/documents/{id}/file")
    @Produces("application/pdf")
    public Response download(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("id") UUID id){
        var document=evidence.download(cookie,project,id);
        return secure(Response.ok(document.bytes(),"application/pdf")
            .header("Content-Disposition","attachment; filename=\""+document.name()+"\""))
            .header("Content-Security-Policy","sandbox")
            .header("Referrer-Policy","no-referrer")
            .build();
    }
    @POST @Path("/documents/{id}/review") @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String cookie,
        @PathParam("project") String project,@PathParam("id") UUID id,
        @HeaderParam("If-Match") String match,OpenAssetEvidence.Decision input){
        var result=evidence.review(cookie,project,id,match,input);
        return secure(Response.ok(result).tag(Integer.toString(result.version()))).build();
    }
    private static Response.ResponseBuilder secure(Response.ResponseBuilder builder){
        return builder.header("Cache-Control","private, no-store")
            .header("X-Content-Type-Options","nosniff");
    }
}
