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
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/source-ingestion")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformSourceIngestionResource {
    private final PlatformSourceIngestion ingestion;

    PlatformSourceIngestionResource(PlatformSourceIngestion ingestion) {
        this.ingestion = ingestion;
    }

    @GET
    public Response list(@CookieParam(AccountAccess.COOKIE) String token,
                         @QueryParam("methodology") String methodology,
                         @QueryParam("edition") String edition,
                         @QueryParam("scenario") String scenario) {
        return noStore(Response.ok(),ingestion.list(token,methodology,edition,scenario));
    }

    @GET
    @Path("/{id}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String token,
                           @PathParam("id") UUID id) {
        return noStore(Response.ok(),ingestion.detail(token,id));
    }

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@CookieParam(AccountAccess.COOKIE) String token,
                           PlatformSourceIngestion.Submit command) {
        return noStore(Response.status(201),ingestion.submit(token,command));
    }

    @POST
    @Path("/{id}/reviews")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response review(@CookieParam(AccountAccess.COOKIE) String token,
                           @PathParam("id") UUID id,
                           @HeaderParam("If-Match") String version,
                           PlatformSourceIngestion.Review decision) {
        return noStore(Response.ok(),ingestion.review(token,id,version(version),decision));
    }

    private static int version(String value) {
        if (value == null || value.isBlank()) throw new PilotProblem(428,"PRECONDICAO_NECESSARIA",
                "If-Match","CF-OPEN-003","Informe a versão do registro.");
        String clean = value.strip().replace("\"", "");
        if (!clean.matches("[1-9][0-9]{0,8}")) {
            throw PilotProblem.invalid("VERSAO_INVALIDA","If-Match","CF-OPEN-003",
                    "Versão do registro inválida.");
        }
        return Integer.parseInt(clean);
    }

    private static Response noStore(Response.ResponseBuilder builder, Object value) {
        return builder.entity(value).header("Cache-Control","private, no-store").build();
    }
}
