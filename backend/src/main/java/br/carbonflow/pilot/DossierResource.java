package br.carbonflow.pilot;

import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
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

import static br.carbonflow.pilot.PilotAccess.*;
import static br.carbonflow.pilot.PilotCommands.key;
import static br.carbonflow.pilot.PilotCommands.expected;

@Path("/v1/projects/{project}/dossiers")
@Produces(MediaType.APPLICATION_JSON)
public class DossierResource {
    private final DossierPackages dossiers;
    private final PilotAccess access;

    DossierResource(DossierPackages dossiers, PilotAccess access) {
        this.dossiers = dossiers;
        this.access = access;
    }

    @GET
    public List<DossierPackages.View> list(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_DOSSIES", null, PREP, REV, PP);
        return dossiers.list(project);
    }

    @GET
    @Path("/checklist")
    public DocumentRules.Checklist checklist(@PathParam("project") String project, @QueryParam("act") DocumentRules.Act act,
                                             @QueryParam("period") String period, @QueryParam("successor") boolean successor) {
        access.require(project, "CONSULTAR_CHECKLIST_DOSSIE", null, PREP, REV, PP);
        if (act == null || period != null && !EnergyImports.validPeriod(period)) {
            throw DossierPackages.invalid("ATO_INVALIDO", "Selecione ato e período do cenário.");
        }
        return DocumentRules.checklist(act, period, successor);
    }

    @GET
    @Path("/{id}/versions/{revision}/preview")
    public List<DossierText.Section> preview(@PathParam("project") String project, @PathParam("id") UUID id,
                                            @PathParam("revision") int revision, @QueryParam("exposure") String exposure) {
        var scope = access.require(project, "PREVER_DOSSIE", id.toString(), PREP, REV, PP);
        var preview = dossiers.preview(project, new DocumentRules.Ref(id, revision), exposure);
        access.allowed(scope, "PREVER_DOSSIE", id.toString());
        return preview;
    }

    @GET
    @Path("/{id}/versions/{revision}/manifest/{exposure}/{edition}")
    public Response manifest(@PathParam("project") String project, @PathParam("id") UUID id,
                             @PathParam("revision") int revision, @PathParam("exposure") String exposure,
                             @PathParam("edition") String edition) {
        var scope = access.require(project, "CONSULTAR_MANIFESTO_DOSSIE", id.toString(), PREP, REV, PP);
        var manifest = dossiers.manifest(project, new DocumentRules.Ref(id, revision), exposure, edition);
        access.allowed(scope, "CONSULTAR_MANIFESTO_DOSSIE", id.toString());
        return Response.ok(manifest.content()).header("X-Content-SHA256", manifest.sha256())
                .header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/{id}/exports")
    public List<DossierPackages.Export> exports(@PathParam("project") String project, @PathParam("id") UUID id) {
        pp(project, "CONSULTAR_EXPORTACOES_DOSSIE", id);
        return dossiers.exports(project, id);
    }

    @POST
    @Path("/{id}/exports")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response export(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                           DossierPackages.ExportInput input) {
        var scope = pp(project, "EXPORTAR_PACOTE", id);
        return Response.status(201).entity(dossiers.export(scope, access.actor().id(), key(commandKey),
                id, expected(version), input)).build();
    }

    @GET
    @Path("/{id}/exports/{exportId}/content")
    @Produces("application/zip")
    public Response download(@PathParam("project") String project, @PathParam("id") UUID id, @PathParam("exportId") UUID exportId) {
        var scope = pp(project, "BAIXAR_EXPORTACAO_DOSSIE", id);
        var bytes = dossiers.exported(project, id, exportId);
        access.allowed(scope, "BAIXAR_EXPORTACAO_DOSSIE", exportId.toString());
        return Response.ok(bytes.content(), "application/zip").header("X-Content-SHA256", bytes.sha256())
                .header("Content-Disposition", "attachment; filename=\"dossie-simulacao.zip\"")
                .header("Cache-Control", "private, no-store").build();
    }

    @GET
    @Path("/{id}/receipts")
    public List<DossierPackages.Receipt> receipts(@PathParam("project") String project, @PathParam("id") UUID id) {
        pp(project, "CONSULTAR_RECIBOS_DOSSIE", id);
        return dossiers.receipts(project, id);
    }

    @POST
    @Path("/{id}/receipts")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response receipt(@PathParam("project") String project, @PathParam("id") UUID id,
                            @HeaderParam("Idempotency-Key") String commandKey, DossierPackages.ReceiptInput input) {
        var scope = pp(project, "CAPTURAR_RECIBO_DOSSIE", id);
        JsonNode captured = dossiers.receipt(scope, access.actor().id(), key(commandKey), id, input);
        return Response.status(201).entity(captured).build();
    }

    private PilotAccess.Scope pp(String project, String action, UUID id) {
        var scope = access.require(project, action, id.toString(), PP);
        if (scope.powerProof() == null || !"SIMULACAO".equals(scope.environment())) {
            throw access.denied(project, action, id.toString(), PilotAccess.forbidden());
        }
        return scope;
    }
}
