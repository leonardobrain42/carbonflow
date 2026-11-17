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
public class AuditRequestResource {
    private final PilotAccess access;
    private final VvbAuditWorkflow audits;
    private final ProgramRequests requests;
    private final PilotDatabase database;
    private final PilotAudit audit;

    AuditRequestResource(PilotAccess access,VvbAuditWorkflow audits,ProgramRequests requests,PilotDatabase database,PilotAudit audit){
        this.access=access;this.audits=audits;this.requests=requests;this.database=database;this.audit=audit;
    }

    @GET
    @Path("/audits/options")
    public Map<String,Object> options(@PathParam("project")String project){
        access.require(project,"OPCOES_AUDITORIA",null,PREP,REV,PP,VVB);
        return Map.of("auditSources",VvbAuditWorkflow.SOURCES,"requestSources",ProgramRequests.SOURCES,"channels",ListingWorkflow.options());
    }

    @GET
    @Path("/audits")
    public List<VvbAuditWorkflow.View> audits(@PathParam("project")String project){
        var scope=access.require(project,"CONSULTAR_AUDITORIAS",null,PP,REV,PREP,VVB);
        return audits.list(scope,access.actor().id());
    }

    @POST
    @Path("/audits")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response createAudit(@PathParam("project")String project,@HeaderParam("Idempotency-Key")String commandKey,
                                VvbAuditWorkflow.Contract input){
        var scope=pp(project,"CONTRATAR_VVB",null);
        return Response.status(201).entity(audits.create(scope,access.actor().id(),key(commandKey),input)).build();
    }

    @POST
    @Path("/audits/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode auditAction(@PathParam("project")String project,@PathParam("id")UUID id,
                                @HeaderParam("Idempotency-Key")String commandKey,@HeaderParam("If-Match")String version,
                                VvbAuditWorkflow.Action input){
        boolean proponent=input!=null&&input.transitionId()!=null&&List.of("TX-E3-28","VINCULAR_CONCESSAO").contains(input.transitionId());
        var scope=proponent?pp(project,"ATUAR_EM_AUDITORIA",id):access.require(project,"ATUAR_EM_AUDITORIA",id.toString(),VVB);
        return audits.act(scope,access.actor().id(),key(commandKey),id,expected(version),input);
    }

    @GET
    @Path("/requests")
    public List<ProgramRequests.View> requests(@PathParam("project")String project,@QueryParam("asOf")OffsetDateTime asOf){
        var scope=access.require(project,"CONSULTAR_PEDIDOS",null,PP,REV,PREP,VVB);
        return requests.list(scope,access.actor().id(),asOf);
    }

    @POST
    @Path("/requests")
    @Consumes(MediaType.APPLICATION_JSON)
    public Response prepareRequest(@PathParam("project")String project,@HeaderParam("Idempotency-Key")String commandKey,ProgramRequests.Create input){
        var scope=pp(project,"PREPARAR_PEDIDO",null);
        return Response.status(201).entity(requests.create(scope,access.actor().id(),key(commandKey),input)).build();
    }

    @POST
    @Path("/requests/{id}/channel-checks")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode checkRequest(@PathParam("project")String project,@PathParam("id")UUID id,
                                 @HeaderParam("Idempotency-Key")String commandKey,@HeaderParam("If-Match")String version,
                                 ListingWorkflow.CheckInput input){
        var scope=pp(project,"CONFERIR_CANAL_PEDIDO",id);
        return requests.check(scope,access.actor().id(),key(commandKey),id,expected(version),input);
    }

    @POST
    @Path("/requests/{id}/actions")
    @Consumes(MediaType.APPLICATION_JSON)
    public JsonNode requestAction(@PathParam("project")String project,@PathParam("id")UUID id,
                                  @HeaderParam("Idempotency-Key")String commandKey,@HeaderParam("If-Match")String version,ProgramRequests.Action input){
        var scope=input!=null&&"TX-E3-36".equals(input.transitionId())?access.require(project,"ATUAR_EM_PEDIDO",id.toString(),VVB):pp(project,"ATUAR_EM_PEDIDO",id);
        return requests.act(scope,access.actor().id(),key(commandKey),id,expected(version),input);
    }

    @GET
    @Path("/audits/{id}/history")
    public List<PilotAudit.Event> auditHistory(@PathParam("project")String project,@PathParam("id")UUID id){
        access.require(project,"CONSULTAR_TRILHA_AUDITORIA",id.toString(),PP,PREP,REV);
        return history(project,id);
    }

    @GET
    @Path("/requests/{id}/history")
    public List<PilotAudit.Event> requestHistory(@PathParam("project")String project,@PathParam("id")UUID id){
        access.require(project,"CONSULTAR_TRILHA_PEDIDO",id.toString(),PP,PREP,REV);
        return history(project,id);
    }

    private List<PilotAudit.Event> history(String project,UUID id){
        return database.read(connection->PilotSql.query(connection,"""
                SELECT id,actor_id,action,outcome,target,occurred_at,details FROM pilot_access_event
                WHERE project_id=? AND target=? ORDER BY occurred_at DESC,id DESC LIMIT 100
                """,row->new PilotAudit.Event(row.getObject(1,UUID.class),row.getString(2),row.getString(3),row.getString(4),
                row.getString(5),row.getObject(6,OffsetDateTime.class),audit.decode(row.getString(7))),project,id.toString()));
    }

    private PilotAccess.Scope pp(String project,String action,UUID id){
        String target=id==null?null:id.toString();
        var scope=access.require(project,action,target,PP);
        if(scope.powerProof()==null)throw access.denied(project,action,target,forbidden());
        return scope;
    }
}
