package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value=AccountAccessTest.Inbox.class,restrictToAnnotatedClass=true)
class OpenMonitoringIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();

    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static final String CHARGER = """
        {"siteCode":"SITE-1","chargerKind":"DCFC","ratedPowerKw":150.0,
         "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
         "sourceReference":"COMMISSION-123"}
        """;
    private static final String ED = """
        {"chargerCode":"DCFC-1","energyKind":"ED","meterPosition":"DOWNSTREAM",
         "sourceSystem":"meter-control-system","calibrationEvidenceRef":"CAL-ED-1",
         "calibrationValidFrom":"2027-01-01T00:00:00Z",
         "calibrationValidUntil":"2028-01-01T00:00:00Z",
         "configuredFrom":"2027-01-01T00:00:00Z",
         "configuredUntil":"2028-01-01T00:00:00Z","timeZone":"America/Sao_Paulo"}
        """;
    private static final String EC = ED.replace("\"ED\"", "\"EC\"")
        .replace("DOWNSTREAM","UPSTREAM").replace("CAL-ED-1","CAL-EC-1");

    @Test
    void ownerPlansDistinctMetersWithImmutableRevisionReplayAndNoSyntheticEnergy() throws Exception {
        Actor owner=actor(),outside=actor();
        String project=project(owner,organization(owner.id()));
        String basePath="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(401,call("GET",basePath,null,null,null,null).statusCode());
        assertEquals(404,call("GET",basePath,outside.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET","/v1/projects/PIL-SYN-BR-001/technical/open-monitoring",
            owner.cookie(),null,null,null).statusCode());

        var empty=call("GET",basePath,owner.cookie(),null,null,null);
        assertEquals(200,empty.statusCode(),empty.body());
        assertTrue(empty.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        assertEquals("PENDING_MEASUREMENT",json(empty).path("readiness").path("state").asText());
        assertTrue(json(empty).at("/readiness/edKwh").isNull());

        String chargerPath=basePath+"/chargers/DCFC-1";
        String key=key();
        var save=call("PUT",chargerPath,owner.cookie(),key,"\"0\"",CHARGER);
        assertEquals(200,save.statusCode(),save.body());
        assertEquals(1,json(save).path("version").asInt());
        assertEquals("\"1\"",save.headers().firstValue("ETag").orElseThrow());
        assertEquals(json(save),json(call("PUT",chargerPath,owner.cookie(),key,"\"0\"",CHARGER)));
        assertEquals(409,call("PUT",chargerPath,owner.cookie(),key,"\"0\"",
            CHARGER.replace("SITE-1","SITE-2")).statusCode());
        assertEquals(412,call("PUT",chargerPath,owner.cookie(),key(),"\"0\"",CHARGER).statusCode());
        assertEquals(428,call("PUT",chargerPath,owner.cookie(),key(),null,CHARGER).statusCode());
        assertEquals(422,call("PUT",chargerPath,owner.cookie(),key(),"\"1\"",
            CHARGER.replace("DCFC","AC").replace("150.0","-1")).statusCode());

        String edPath=basePath+"/meters/ED-METER-1";
        assertEquals(200,call("PUT",edPath,owner.cookie(),key(),"\"0\"",ED).statusCode());
        assertEquals(200,call("PUT",basePath+"/meters/EC-METER-1",owner.cookie(),key(),"\"0\"",EC).statusCode());
        var view=call("GET",basePath+"?from=2027-02-01T00%3A00%3A00Z&until=2027-03-01T00%3A00%3A00Z",
            owner.cookie(),null,null,null);
        assertEquals(200,view.statusCode(),view.body());
        JsonNode state=json(view);
        assertEquals(1,state.path("chargers").size());
        assertEquals(2,state.path("meters").size());
        assertTrue(state.at("/readiness/edKwh").isNull());
        assertTrue(state.at("/readiness/ecKwh").isNull());
        assertFalse(state.at("/readiness/canUseForE8").asBoolean());
        assertTrue(state.at("/readiness/gates").toString().contains("CALIBRATION_NOT_REVIEWED"));
        assertFalse(state.toString().contains("SIM-"));
        assertEquals(0L,database.read(c->first(c,
            "SELECT count(*) FROM pilot_import_file WHERE project_id=?",r->r.getLong(1),project)).longValue());
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_charger_revision WHERE project_id=?",r->r.getLong(1),project)).longValue());

        var changed=call("PUT",chargerPath,owner.cookie(),key(),"\"1\"",
            CHARGER.replace("150.0","180.0"));
        assertEquals(200,changed.statusCode(),changed.body());
        assertEquals(2,json(changed).path("version").asInt());
        var stale=json(call("GET",basePath,owner.cookie(),null,null,null));
        assertTrue(stale.at("/readiness/gates").toString().contains("CHARGER_CONFIGURATION_STALE"));
        assertEquals(2L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_charger_revision WHERE project_id=?",r->r.getLong(1),project)).longValue());
        assertEquals(200,call("PUT",edPath,owner.cookie(),key(),"\"1\"",ED).statusCode());
        assertEquals(200,call("PUT",basePath+"/meters/EC-METER-1",
            owner.cookie(),key(),"\"1\"",EC).statusCode());
        var synced=json(call("GET",basePath,owner.cookie(),null,null,null));
        assertFalse(synced.at("/readiness/gates").toString().contains("CHARGER_CONFIGURATION_STALE"));
        assertTrue(synced.at("/readiness/edKwh").isNull());
    }

    @Test
    void onlyPreparerWithMonitoringGrantCanEditReviewerCanReadAndRevokeBlocks() throws Exception {
        Actor owner=actor(),preparer=actor(),reviewer=actor(),outside=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        String path="/v1/projects/"+project+"/technical/open-monitoring";
        UUID prepGrant=grant(project,org,owner.id(),preparer.id(),"PREP");
        grant(project,org,owner.id(),reviewer.id(),"REV");

        assertEquals(200,call("GET",path,preparer.cookie(),null,null,null).statusCode());
        assertEquals(200,call("GET",path,reviewer.cookie(),null,null,null).statusCode());
        assertEquals(404,call("PUT",path+"/chargers/REVIEWER",
            reviewer.cookie(),key(),"\"0\"",CHARGER).statusCode());
        assertEquals(404,call("GET",path,outside.cookie(),null,null,null).statusCode());
        assertEquals(200,call("PUT",path+"/chargers/PREP-1",
            preparer.cookie(),key(),"\"0\"",CHARGER).statusCode());
        assertEquals(404,call("GET","/v1/projects/"+project+"/technical/open-calculation-readiness",
            preparer.cookie(),null,null,null).statusCode());
        database.transaction(c -> {
            update(c,"""
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=?
                WHERE id=?
                """,owner.id(),prepGrant);
            return null;
        });
        assertEquals(404,call("GET",path,preparer.cookie(),null,null,null).statusCode());
        assertEquals(404,call("PUT",path+"/chargers/PREP-1",
            preparer.cookie(),key(),"\"1\"",CHARGER).statusCode());
        assertTrue(database.read(c->first(c,"""
            SELECT count(*) FROM app_project_technical_access_event
            WHERE actor_id=? AND target_digest=? AND action='SAVE_OPEN_MONITORING'
              AND outcome='DENIED'
            """,r->r.getLong(1),preparer.id(),AccessSecrets.digest(project)))>=1);
    }

    private UUID grant(String project,UUID org,UUID owner,UUID actor,String role) {
        UUID id=UUID.randomUUID();
        database.transaction(c -> {
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, ?, ARRAY['MONITORING']::text[],
                   statement_timestamp()+interval '3 days')
                """,id,project,org,actor,owner,role);
            return null;
        });
        return id;
    }
    private Actor actor() {
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c -> {
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Monitoramento teste','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner) {
        UUID id=UUID.randomUUID();
        database.transaction(c -> {
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Medidores teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });
        return id;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var result=call("POST","/v1/projects",owner.cookie(),key(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Rede aberta de medição\"}");
        assertEquals(201,result.statusCode(),result.body());
        return json(result).path("projectId").asText();
    }
    private HttpResponse<String> call(String method,String path,String cookie,String key,
                                      String match,String data) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(match!=null)builder.header("If-Match",match);
        if(data!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> result) throws Exception {
        return mapper.readTree(result.body());
    }
}
