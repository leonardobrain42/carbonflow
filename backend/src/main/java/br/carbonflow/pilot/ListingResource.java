package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
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
import static br.carbonflow.pilot.PilotCommands.*;

@Path("/v1/projects/{project}")
@Produces(MediaType.APPLICATION_JSON)
public class ListingResource {
    private final PilotAccess access;
    private final ListingWorkflow listings;
    private final ConsultationWorkflow consultations;

    ListingResource(PilotAccess access, ListingWorkflow listings, ConsultationWorkflow consultations) {
        this.access = access;
        this.listings = listings;
        this.consultations = consultations;
    }

    @GET
    @Path("/listings/options")
    public Map<String, Object> options(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_OPCOES_LISTAGEM", null, PREP, PP, REV, VVB);
        return ListingWorkflow.options();
    }

    @GET
    @Path("/listings")
    public List<ListingWorkflow.View> list(@PathParam("project") String project, @QueryParam("asOf") OffsetDateTime asOf) {
        access.require(project, "CONSULTAR_LISTAGENS", null, PREP, PP, REV);
        return listings.list(project, asOf);
    }

    @GET
    @Path("/listings/{id}/history")
    public List<PilotAudit.Event> history(@PathParam("project") String project, @PathParam("id") UUID id) {
        access.require(project, "CONSULTAR_TRILHA_LISTAGEM", id.toString(), PREP, PP, REV);
        return listings.history(project, id);
    }

    @GET
    @Path("/consultations/assigned")
    public List<ConsultationWorkflow.Consultation> assigned(@PathParam("project") String project) {
        access.require(project, "CONSULTAR_CONSULTAS_ATRIBUIDAS", null, VVB);
        return consultations.assigned(project, access.actor().id());
    }

    @POST
    @Path("/listings")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response create(@PathParam("project") String project, @HeaderParam("Idempotency-Key") String commandKey, ListingWorkflow.Create input) {
        var scope = pp(project, "PREPARAR_LISTAGEM", null);
        return Response.status(201).entity(listings.create(scope, access.actor().id(), key(commandKey), input)).build();
    }

    @POST
    @Path("/listings/{id}/channel-checks")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode check(@PathParam("project") String project, @PathParam("id") UUID id,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                          ListingWorkflow.CheckInput input) {
        var scope = pp(project, "CONFERIR_CANAL_LISTAGEM", id);
        return listings.check(scope, access.actor().id(), key(commandKey), id, expected(version), input);
    }

    @GET
    @Path("/listings/{id}/readiness")
    public ListingWorkflow.View readiness(@PathParam("project") String project, @PathParam("id") UUID id,
                                          @QueryParam("effectiveAt") OffsetDateTime effectiveAt) {
        access.require(project, "CONFERIR_PRONTIDAO_LISTAGEM", id.toString(), PREP, PP, REV);
        return listings.readiness(project, id, effectiveAt);
    }

    @POST
    @Path("/listings/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode action(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version, ListingWorkflow.Action action) {
        var scope = pp(project, "REGISTRAR_ACAO_LISTAGEM", id);
        return listings.act(scope, access.actor().id(), key(commandKey), id, expected(version), action);
    }

    @GET
    @Path("/consultations/{id}")
    public ConsultationWorkflow.Consultation consultation(@PathParam("project") String project, @PathParam("id") UUID id) {
        access.require(project, "CONSULTAR_CONSULTA", id.toString(), PREP, PP, REV);
        return consultations.get(project, id);
    }

    @GET
    @Path("/consultations/{id}/public")
    public Response publicView(@PathParam("project") String project, @PathParam("id") UUID id) {
        return Response.ok(consultations.publicView(project, id)).header("Cache-Control", "no-store").build();
    }

    @POST
    @Path("/consultations/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode close(@PathParam("project") String project, @PathParam("id") UUID id,
                          @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                          ConsultationWorkflow.Close input) {
        var scope = pp(project, "TX-E3-24", id);
        return consultations.close(scope, access.actor().id(), key(commandKey), id, expected(version), input);
    }

    @GET
    @Path("/consultations/{id}/comments")
    public Response comments(@PathParam("project") String project, @PathParam("id") UUID id) {
        var scope = access.require(project, "CONSULTAR_MANIFESTACOES", id.toString(), PREP, PP, REV, VVB);
        return Response.ok(consultations.comments(scope, access.actor().id(), id)).header("Cache-Control", "private, no-store").build();
    }

    @POST
    @Path("/consultations/{id}/comments")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response submit(@PathParam("project") String project, @PathParam("id") UUID id,
                           @HeaderParam("Idempotency-Key") String commandKey, ConsultationWorkflow.Submission input) {
        var scope = pp(project, "TX-E3-20", id);
        return Response.status(201).entity(consultations.submit(scope, access.actor().id(), key(commandKey), id, input)).build();
    }

    @POST
    @Path("/comments/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode commentAction(@PathParam("project") String project, @PathParam("id") UUID id,
                                  @HeaderParam("Idempotency-Key") String commandKey, @HeaderParam("If-Match") String version,
                                  ConsultationWorkflow.CommentAction input) {
        var scope = input != null && "TX-E3-23".equals(input.transitionId())
                ? access.require(project, "TX-E3-23", id.toString(), VVB) : pp(project, "REGISTRAR_ACAO_MANIFESTACAO", id);
        return consultations.commentAction(scope, access.actor().id(), key(commandKey), id, expected(version), input);
    }

    private PilotAccess.Scope pp(String project, String action, UUID id) {
        String target = id == null ? null : id.toString();
        var scope = access.require(project, action, target, PP);
        if (scope.powerProof() == null) throw access.denied(project, action, target, PilotAccess.forbidden());
        return scope;
    }
}
