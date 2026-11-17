package br.carbonflow.pilot;

import java.io.IOException;
import java.io.InputStream;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import com.fasterxml.jackson.databind.JsonNode;

import static br.carbonflow.pilot.EnergyImportModel.*;
import static br.carbonflow.pilot.EnergyImports.invalid;
import static br.carbonflow.pilot.PilotCommands.*;
import static br.carbonflow.pilot.PilotAccess.PREP;
import static br.carbonflow.pilot.PilotAccess.REV;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class EnergyImportResource {
    private static final String CSV = "text/csv";
    private static final String OP = "OP";

    private final PilotAccess access;
    private final EnergyImports imports;
    private final EnergyReviews reviews;

    EnergyImportResource(PilotAccess access, EnergyImports imports, EnergyReviews reviews) {
        this.access = access;
        this.imports = imports;
        this.reviews = reviews;
    }

    @GET
    @Path("/imports/options")
    public Map<String, Object> options(@PathParam("project") String project) {
        internal(project, "CONSULTAR_MAPEAMENTO_ED_EC", null);
        return Map.of("sourceId", SOURCE, "authorization", new CodeRef(AUTHORIZATION, 1),
                "mappingProfile", new CodeRef(MAPPING, 1), "periods", List.of(new CodeRef("M1", 1), new CodeRef("M2", 1)),
                "maxBytes", MAX_BYTES, "maxLines", MAX_LINES, "header", HEADER);
    }

    @GET
    @Path("/imports/example")
    @Produces(CSV)
    public Response sample(@PathParam("project") String project) throws IOException {
        internal(project, "CONSULTAR_MASSA_ED_EC", null);
        try (var sample = getClass().getResourceAsStream("/fixtures/e8-004-ed-ec.csv")) {
            if (sample == null) throw new IllegalStateException("Massa sintética indisponível");
            return download("e8-004-ed-ec.csv", sample.readAllBytes());
        }
    }

    @GET
    @Path("/imports/example/monitoring/{period}")
    @Produces(CSV)
    public Response monitoringSample(@PathParam("project") String project, @PathParam("period") String period) throws IOException {
        access.require(project, "CONSULTAR_MASSA_MONITORAMENTO", null, OP, PREP, REV, PilotAccess.PP);
        String name = "e8-006-" + period(period).toLowerCase(java.util.Locale.ROOT) + ".csv";
        try (var sample = getClass().getResourceAsStream("/fixtures/" + name)) {
            if (sample == null) throw new IllegalStateException("Massa de monitoramento indisponível");
            return download(name, sample.readAllBytes());
        }
    }

    @POST
    @Path("/artifacts")
    @Consumes({CSV, MediaType.APPLICATION_OCTET_STREAM})
    public Response upload(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                           @HeaderParam("X-File-Name") String name, @HeaderParam("X-Source-Id") String source,
                           @HeaderParam("X-Source-Authorization") String authorization, InputStream body) throws IOException {
        var scope = access.require(project, "RECEBER_CSV_ED_EC", null, OP, PREP);
        String command = key(commandKey);
        try {
            if (name != null) name = java.net.URLDecoder.decode(name, java.nio.charset.StandardCharsets.UTF_8);
        } catch (IllegalArgumentException error) {
            throw invalid("ARQUIVO_INVALIDO", "fileName", "O nome do arquivo está com codificação inválida.");
        }
        return Response.status(201).entity(imports.upload(scope, access.actor().id(), command, name, source, authorization,
                body == null ? new byte[0] : body.readNBytes(MAX_BYTES + 1))).build();
    }

    @GET
    @Path("/imports/files/{id}/versions/1/content")
    @Produces(CSV)
    public Response raw(@PathParam("project") String project, @PathParam("id") UUID id) {
        var scope = internal(project, "BAIXAR_CSV_ED_EC", id.toString());
        try {
            var file = imports.content(project, id);
            access.allowed(scope, "BAIXAR_CSV_ED_EC", id.toString());
            return download(file.name(), file.bytes());
        } catch (PilotProblem problem) {
            throw access.denied(project, "BAIXAR_CSV_ED_EC", id.toString(), problem);
        }
    }

    @POST
    @Path("/imports")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response enqueue(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey,
                            ImportCommand command) {
        var scope = access.require(project, "SOLICITAR_IMPORTACAO_ED_EC", null, OP, PREP);
        return Response.accepted(imports.enqueue(scope, access.actor().id(), key(commandKey), command)).build();
    }

    @GET
    @Path("/imports")
    public List<Job> jobs(@PathParam("project") String project) {
        internal(project, "CONSULTAR_IMPORTACOES_ED_EC", null);
        return imports.jobs(project);
    }

    @GET
    @Path("/imports/{id}")
    public Report report(@PathParam("project") String project, @PathParam("id") UUID id) {
        internal(project, "CONSULTAR_IMPORTACAO_ED_EC", id.toString());
        return imports.report(project, id);
    }

    @GET
    @Path("/imports/{id}/rows")
    public Page rows(@PathParam("project") String project, @PathParam("id") UUID id, @QueryParam("cursor") String cursor) {
        internal(project, "CONSULTAR_PREVIA_ED_EC", id.toString());
        return imports.rows(project, id, cursor);
    }

    @POST
    @Path("/imports/{id}/resume")
    public Response resume(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version) {
        var scope = access.require(project, "RETOMAR_IMPORTACAO_ED_EC", id.toString(), OP, PREP);
        return Response.accepted(imports.resume(scope, access.actor().id(), key(commandKey), id, expected(version))).build();
    }


    @POST
    @Path("/observations/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode decision(@PathParam("project") String project, @PathParam("id") UUID id,
                             @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                             EnergyReviews.Action action) {
        String transition = action == null ? null : action.transitionId();
        if (transition == null || !List.of("PROPOR", "ADMITIR", "REJEITAR").contains(transition)) {
            throw invalid("ACAO_INVALIDA", "transitionId", "Selecione uma ação de revisão.");
        }
        var scope = access.require(project, transition + "_ED_EC", id.toString(), "PROPOR".equals(transition) ? PREP : REV);
        return reviews.act(scope, access.actor().id(), key(commandKey), id, expected(version), action);
    }

    @GET
    @Path("/observations")
    public List<Observation> observations(@PathParam("project") String project, @QueryParam("period") String period) {
        internal(project, "CONSULTAR_OBSERVACOES_ED_EC", null);
        return reviews.active(project, period(period));
    }

    @GET
    @Path("/observations/{id}/versions/{version}")
    public Observation observation(@PathParam("project") String project, @PathParam("id") UUID id,
                                   @PathParam("version") int version) {
        internal(project, "CONSULTAR_VERSAO_ED_EC", id.toString());
        return reviews.version(project, new Ref(id, version));
    }

    @POST
    @Path("/periods/{period}/selections")
    public Response selection(@PathParam("project") String project, @PathParam("period") String period,
                              @HeaderParam("Idempotency-Key") String commandKey) {
        var scope = access.require(project, "CONGELAR_SELECAO_ED_EC", period, REV);
        return Response.status(201).entity(reviews.freeze(scope, access.actor().id(), key(commandKey), period(period))).build();
    }

    @GET
    @Path("/periods/{period}/selections")
    public List<Selection> selections(@PathParam("project") String project, @PathParam("period") String period) {
        internal(project, "CONSULTAR_SELECOES_ED_EC", period);
        return reviews.selections(project, period(period));
    }

    @GET
    @Path("/periods/{period}/selections/{id}")
    public SelectionPage frozen(@PathParam("project") String project, @PathParam("period") String period,
                                @PathParam("id") UUID id, @QueryParam("cursor") String cursor) {
        internal(project, "CONSULTAR_SELECAO_ED_EC", id.toString());
        return reviews.selection(project, period(period), id, cursor);
    }

    private String period(String period) {
        if (!EnergyImports.validPeriod(period)) throw invalid("PERIODO_INVALIDO", "period", "Selecione M1 ou M2.");
        return period;
    }

    private PilotAccess.Scope internal(String project, String action, String target) {
        return access.require(project, action, target, OP, PREP, REV);
    }

    private Response download(String name, byte[] bytes) {
        String encoded = java.net.URLEncoder.encode(name, java.nio.charset.StandardCharsets.UTF_8).replace("+", "%20");
        return Response.ok(bytes, "text/csv;charset=UTF-8")
                .header("Content-Disposition", "attachment; filename*=UTF-8''" + encoded).build();
    }
}
