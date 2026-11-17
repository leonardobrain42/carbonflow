package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.util.List;
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

import static br.carbonflow.pilot.PilotAccess.PP;
import static br.carbonflow.pilot.PilotAccess.PREP;
import static br.carbonflow.pilot.PilotAccess.REV;
import static br.carbonflow.pilot.PilotAccess.VVB;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class ProjectAccessResource {
    public record DossierCommand(String sample, DocumentRules.Input document) {}
    public record ReviewDetails(ProtectedArtifacts.Reference artifact) {}
    public record ReviewCommand(String transitionId, ReviewDetails details, DocumentRules.Ref dossier,
                                String reason, String item, String responsible, String expectedCorrection) {}
    public record GrantCommand(ProtectedArtifacts.Reference artifact, String purpose, String recipient,
                               OffsetDateTime expiresAt) {}

    private final PilotAccess access;
    private final ProtectedArtifacts artifacts;
    private final PilotAudit audit;
    private final DossierPackages dossiers;

    ProjectAccessResource(PilotAccess access, ProtectedArtifacts artifacts, PilotAudit audit, DossierPackages dossiers) {
        this.access = access;
        this.artifacts = artifacts;
        this.audit = audit;
        this.dossiers = dossiers;
    }

    @GET
    @Path("/artifacts")
    public List<ProtectedArtifacts.Artifact> search(@PathParam("project") String project, @QueryParam("q") String search) {
        var scope = access.require(project, "BUSCAR_ARTEFATOS", null, PREP, REV, PP, VVB);
        if (search != null && search.length() > 150) {
            throw badRequest("q");
        }
        return artifacts.search(scope, access.actor().id(), search == null ? "" : search.strip());
    }

    @GET
    @Path("/artifacts/{id}/versions/{version}/preview")
    public ProtectedArtifacts.Preview preview(@PathParam("project") String project, @PathParam("id") String id,
                                                @PathParam("version") int version) {
        var scope = access.require(project, "PREVER_ARTEFATO", id, PREP, REV, PP, VVB);
        return artifacts.preview(scope, access.actor().id(), new ProtectedArtifacts.Reference(resourceId(id), version));
    }

    @GET
    @Path("/artifacts/{id}/versions/{version}/content")
    @Produces({MediaType.TEXT_PLAIN, "application/zip"})
    public Response download(@PathParam("project") String project, @PathParam("id") String id,
                             @PathParam("version") int version) {
        var scope = access.require(project, "BAIXAR_ARTEFATO", id, PREP, REV, PP, VVB);
        var reference = new ProtectedArtifacts.Reference(resourceId(id), version);
        var content = artifacts.download(scope, access.actor().id(), reference);
        boolean zip = "application/zip".equals(content.mediaType());
        return Response.ok(content.content(), zip ? content.mediaType() : "text/plain;charset=UTF-8")
                .header("Cache-Control", "private, no-store")
                .header("Content-Disposition", "attachment; filename=\"artefato-" + reference.version() + (zip ? ".zip" : ".txt") + "\"")
                .build();
    }

    @POST
    @Path("/dossiers")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String ifMatch,
                           DossierCommand command) {
        var scope = access.require(project, "PREPARAR_DOSSIE", null, PREP);
        if (command != null && command.document() != null && command.sample() == null) {
            return Response.status(201).entity(dossiers.prepare(scope, access.actor().id(), key(key),
                    PilotCommands.expected(ifMatch), command.document())).build();
        }
        if (command == null || command.document() != null || !ProtectedArtifacts.SAMPLE.equals(command.sample())) {
            throw badRequest("sample");
        }
        return Response.status(201).entity(artifacts.createDossier(scope, access.actor().id(), key(key))).build();
    }

    @POST
    @Path("/dossiers/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode review(@PathParam("project") String project, @PathParam("id") String id,
                           @HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String ifMatch,
                           ReviewCommand command) {
        if (command != null && command.dossier() != null && command.transitionId() != null
                && List.of("TX-E3-06", "TX-E3-07", "TX-E3-08").contains(command.transitionId()) && command.details() == null) {
            String role = "TX-E3-06".equals(command.transitionId()) ? PREP : REV;
            var scope = access.require(project, command.transitionId(), id, role);
            var action = new DossierPackages.Action(command.transitionId(), command.dossier(), command.reason(), command.item(),
                    command.responsible(), command.expectedCorrection());
            return dossiers.action(scope, access.actor().id(), key(key), resourceId(id), expected(ifMatch), action);
        }
        var scope = access.require(project, "APROVAR_ARTEFATO", id, REV);
        if (command == null || !"TX-E3-08".equals(command.transitionId()) || command.details() == null) {
            throw badRequest("transitionId");
        }
        return artifacts.approve(scope, access.actor().id(), key(key), resourceId(id),
                validReference(command.details().artifact()), expected(ifMatch));
    }

    @GET
    @Path("/grants")
    public List<ProtectedArtifacts.Grant> grants(@PathParam("project") String project) {
        var scope = proponent(project, "CONSULTAR_CONCESSOES", null);
        var grants = artifacts.grants(scope);
        access.allowed(scope, "CONSULTAR_CONCESSOES", null);
        return grants;
    }

    @POST
    @Path("/grants")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response grant(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String key,
                          GrantCommand command) {
        var scope = proponent(project, "CONCEDER_ACESSO", null);
        if (command == null || command.purpose() == null || command.expiresAt() == null || command.recipient() == null
                || !command.purpose().matches("AUDITORIA_(VAL|V[12])") || !command.recipient().matches("SIM-[A-Z-]{1,32}")) {
            throw badRequest("grant");
        }
        return Response.status(201).entity(artifacts.grant(scope, access.actor().id(), key(key),
                validReference(command.artifact()), command.purpose(), command.recipient(), command.expiresAt())).build();
    }

    @POST
    @Path("/grants/{id}/revoke")
    public JsonNode revoke(@PathParam("project") String project, @PathParam("id") String id,
                           @HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String ifMatch) {
        var scope = proponent(project, "REVOGAR_ACESSO", id);
        return artifacts.revoke(scope, access.actor().id(), key(key), resourceId(id), expected(ifMatch));
    }

    @POST
    @Path("/grants/{id}/receipts")
    public JsonNode receipt(@PathParam("project") String project, @PathParam("id") String id,
                            @HeaderParam("Idempotency-Key") String key, @HeaderParam("If-Match") String ifMatch) {
        var scope = proponent(project, "CAPTURAR_RECIBO", id);
        return artifacts.captureReceipt(scope, access.actor().id(), key(key), resourceId(id), expected(ifMatch));
    }

    @GET
    @Path("/access-events")
    public List<PilotAudit.Event> events(@PathParam("project") String project) {
        return audit.events(access.require(project, "CONSULTAR_TRILHA", null, PP, REV), 100);
    }

    private PilotAccess.Scope proponent(String project, String action, String target) {
        var scope = access.require(project, action, target, PP);
        if (scope.powerProof() == null) {
            throw access.denied(project, action, target, PilotAccess.forbidden());
        }
        return scope;
    }

    private ProtectedArtifacts.Reference validReference(ProtectedArtifacts.Reference reference) {
        if (reference == null || reference.id() == null || reference.version() < 1) {
            throw badRequest("artifact");
        }
        return reference;
    }

    private String key(String key) {
        if (key == null || !key.matches("[A-Za-z0-9._-]{1,128}")) {
            throw badRequest("Idempotency-Key");
        }
        return key;
    }

    private int expected(String etag) {
        if (etag == null) {
            throw new PilotProblem(428, "PRECONDICAO_VERSIONADA_AUSENTE", "If-Match", "OP-E6-03/08",
                    "Informe a versão atual do registro.");
        }
        if (!etag.matches("\"[1-9][0-9]{0,8}\"")) {
            throw badRequest("If-Match");
        }
        return Integer.parseInt(etag.substring(1, etag.length() - 1));
    }

    private UUID resourceId(String value) {
        try {
            UUID id = UUID.fromString(value);
            if (id.toString().equalsIgnoreCase(value)) {
                return id;
            }
        } catch (IllegalArgumentException ignored) {
            // Identificadores inválidos e invisíveis recebem a mesma resposta.
        }
        throw PilotAccess.invisible();
    }

    private PilotProblem badRequest(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "OP-E6-03/08", "Conferir os campos do comando.");
    }
}
