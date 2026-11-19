package br.carbonflow.pilot;

import jakarta.ws.rs.CookieParam;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/platform/source-work")
@Produces(MediaType.APPLICATION_JSON)
public class PlatformSourceWorkResource {
    private final PlatformSourceWork work;

    PlatformSourceWorkResource(PlatformSourceWork work) {
        this.work = work;
    }

    @GET
    public Response board(@CookieParam(AccountAccess.COOKIE) String token,
                          @QueryParam("methodology") String methodology,
                          @QueryParam("edition") String edition,
                          @QueryParam("scenario") String scenario) {
        return noStore(work.board(token,methodology,edition,scenario));
    }

    @GET
    @Path("/assignees")
    public Response assignees(@CookieParam(AccountAccess.COOKIE) String token) {
        return noStore(work.assignees(token));
    }

    @GET
    @Path("/{needCode}")
    public Response detail(@CookieParam(AccountAccess.COOKIE) String token,
                           @PathParam("needCode") String code) {
        return noStore(work.detail(token,code));
    }

    @PUT
    @Path("/{needCode}")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response request(@CookieParam(AccountAccess.COOKIE) String token,
                            @PathParam("needCode") String code,
                            PlatformSourceWork.NewRequest input) {
        return noStore(work.request(token,code,input));
    }

    @POST
    @Path("/{needCode}/revisions")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response revise(@CookieParam(AccountAccess.COOKIE) String token,
                           @PathParam("needCode") String code,
                           @HeaderParam("If-Match") String header,
                           PlatformSourceWork.Change input) {
        return noStore(work.revise(token,code,expectedVersion(header),input));
    }

    private static int expectedVersion(String header) {
        if (header == null || header.isBlank()) throw new PilotProblem(428,"PRECONDICAO_NECESSARIA",
                "If-Match","CF-OPEN-002","Informe a versão da solicitação para atualizar.");
        String normalized = header.strip().replace("\"", "");
        try {
            int version = Integer.parseInt(normalized);
            if (version > 0) return version;
        } catch (NumberFormatException ignored) {
            // Report only a public error, not the supplied invalid header.
        }
        throw PilotProblem.invalid("VERSAO_INVALIDA","If-Match","CF-OPEN-002",
                "Versão da solicitação inválida.");
    }

    private static Response noStore(Object value) {
        return Response.ok(value).header("Cache-Control","private, no-store").build();
    }
}
