package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
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
class OpenMeasurementIngestionIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();

    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static final String SOURCE="METER-API-1";
    private static final String CHARGER="""
        {"siteCode":"SITE-1","chargerKind":"DCFC","ratedPowerKw":150,
         "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
         "sourceReference":"COMMISSION-123"}
        """;
    private static String meter(String kind) {
        return """
          {"chargerCode":"DCFC-1","energyKind":"%s","meterPosition":"%s",
           "sourceSystem":"METER-API-1","calibrationEvidenceRef":"CAL-2027-01",
           "calibrationValidFrom":"2027-01-01T00:00:00Z",
           "calibrationValidUntil":"2028-01-01T00:00:00Z",
           "configuredFrom":"2027-01-01T00:00:00Z",
           "configuredUntil":"2028-01-01T00:00:00Z","timeZone":"America/Sao_Paulo"}
          """.formatted(kind,"ED".equals(kind)?"DOWNSTREAM":"UPSTREAM");
    }

    private static final String HEADER=
        "meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n";
    private static final String DATA=HEADER+
        "ED-1,0001,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2700,kWh\n"+
        "EC-1,0001,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,3000,kWh\n";

    @Test
    void encryptedRealCsvCreatesOnlyCandidatesAndDeduplicatesWithoutMutatingPilot() throws Exception {
        Actor owner=actor(),outside=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        String second=project(outside,organization(outside.id()));
        configure(owner,project);
        String batches="/v1/projects/"+project+"/technical/open-monitoring/batches";
        String idempotency=key();
        assertEquals(401,upload(batches,null,idempotency,DATA).statusCode());
        assertEquals(404,upload(batches, outside.cookie(),key(),DATA).statusCode());
        var uploaded=upload(batches,owner.cookie(),idempotency,DATA);
        assertEquals(202,uploaded.statusCode(),uploaded.body());
        JsonNode received=read(uploaded);
        assertEquals("CANDIDATE",received.path("status").asText());
        assertEquals(2,received.path("rowCount").asInt());
        assertEquals("2700.000000000000",received.at("/rows/0/kwh").asText());
        assertEquals("3000.000000000000",received.at("/rows/1/kwh").asText());
        assertEquals("CANDIDATE",received.at("/rows/0/state").asText());
        assertFalse(uploaded.body().contains("encrypted"));
        assertFalse(uploaded.body().contains("ciphertext"));
        assertEquals(received,read(upload(batches,owner.cookie(),idempotency,DATA)));
        assertEquals(received,read(upload(batches,owner.cookie(),key(),DATA)));
        assertEquals(409,upload(batches,owner.cookie(),idempotency,
            DATA.replace("2700","2800")).statusCode());

        UUID batch=UUID.fromString(received.path("id").asText());
        assertEquals(200,call("GET",batches+"/"+batch,owner.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET","/v1/projects/"+second+"/technical/open-monitoring/batches/"+batch,
            outside.cookie(),null,null,null).statusCode());
        assertEquals(1,read(call("GET",batches,owner.cookie(),null,null,null)).size());

        long[] counts=database.read(c->new long[]{
            first(c,"SELECT count(*) FROM app_project_evidence_blob WHERE project_id=?",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM app_open_measurement_batch WHERE project_id=?",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM pilot_import_file WHERE project_id=?",
                r->r.getLong(1),project)});
        assertArrayEquals(new long[]{1,1,0},counts);
        byte[] cipher=database.read(c->first(c,"""
            SELECT encrypted FROM app_project_evidence_blob WHERE project_id=?
            """,r->r.getBytes(1),project));
        assertNotNull(cipher);
        assertFalse(new String(cipher,StandardCharsets.ISO_8859_1).contains("2700"));
        assertNotEquals(DATA,new String(cipher,StandardCharsets.UTF_8));

        String changed=DATA.replace("2700","2800");
        JsonNode quarantine=read(uploadNamed(batches,owner.cookie(),key(),
            "meter-reading-2.csv",changed));
        assertEquals("QUARANTINED",quarantine.path("status").asText());
        assertTrue(quarantine.at("/rows/0/reasons").toString().contains("DUPLICATE_SOURCE_INTERVAL"));
        assertTrue(quarantine.at("/rows/1/reasons").toString().contains("DUPLICATE_SOURCE_INTERVAL"));
        assertNull(quarantine.at("/rows/0/kwh").textValue());
        assertEquals(2L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_measurement_batch WHERE project_id=?",
            r->r.getLong(1),project)).longValue());
        assertEquals(0L,database.read(c->first(c,
            "SELECT count(*) FROM pilot_selected_run WHERE project_id=?",
            r->r.getLong(1),project)).longValue());
    }

    @Test
    void operatorCanUploadButCannotModifyInventoryOrReadFinancialCalculation() throws Exception {
        Actor owner=actor(),op=actor(),rev=actor(),external=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        configure(owner,project);
        UUID permission=grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),rev.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        String batches=root+"/batches";
        assertEquals(202,upload(batches,op.cookie(),key(),DATA).statusCode());
        assertEquals(404,call("PUT",root+"/chargers/CHG-OTHER",op.cookie(),
            key(),"\"0\"",CHARGER).statusCode());
        assertEquals(404,call("GET","/v1/projects/"+project+"/technical/open-calculation-readiness",
            op.cookie(),null,null,null).statusCode());
        assertEquals(404,upload(batches,rev.cookie(),key(),DATA).statusCode());
        assertEquals(200,call("GET",batches,rev.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET",batches,external.cookie(),null,null,null).statusCode());

        database.transaction(c->{
            update(c,"""
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=?
                WHERE id=?
                """,owner.id(),permission);
            return null;
        });
        assertEquals(404,upload(batches,op.cookie(),key(),DATA).statusCode());
        assertEquals(404,call("GET",batches,op.cookie(),null,null,null).statusCode());
    }

    @Test
    void malformedAndUnconfiguredSourceDoNotPublishReadings() throws Exception {
        Actor owner=actor();
        String project=project(owner,organization(owner.id()));
        configure(owner,project);
        String batches="/v1/projects/"+project+"/technical/open-monitoring/batches";
        assertEquals(422,upload(batches,owner.cookie(),key(),
            "meter,amount\nED-1,100\n").statusCode());
        String old=DATA.replace("2700","0").replace("3000","-1");
        JsonNode bad=read(upload(batches,owner.cookie(),key(),old));
        assertEquals("QUARANTINED",bad.path("status").asText());
        assertTrue(bad.path("rows").toString().contains("ZERO_REQUIRES_EVIDENCE"));
        assertTrue(bad.path("rows").toString().contains("NEGATIVE_ENERGY"));
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_measurement_batch WHERE project_id=?",
            r->r.getLong(1),project)).longValue());
    }

    private void configure(Actor owner,String project) throws Exception {
        String base="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(200,call("PUT",base+"/chargers/DCFC-1",
            owner.cookie(),key(),"\"0\"",CHARGER).statusCode());
        assertEquals(200,call("PUT",base+"/meters/ED-1",
            owner.cookie(),key(),"\"0\"",meter("ED")).statusCode());
        assertEquals(200,call("PUT",base+"/meters/EC-1",
            owner.cookie(),key(),"\"0\"",meter("EC")).statusCode());
    }

    private UUID grant(String project,UUID org,UUID owner,UUID actor,String role) {
        UUID id=UUID.randomUUID();
        database.transaction(c->{
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
        UUID id=UUID.randomUUID();
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Medição teste','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID actor) {
        UUID id=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Medição real teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",id,actor);
            return null;
        });
        return id;
    }
    private String project(Actor actor,UUID org) throws Exception {
        var response=call("POST","/v1/projects",actor.cookie(),key(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Teste ED EC\"}");
        assertEquals(201,response.statusCode(),response.body());
        return read(response).path("projectId").asText();
    }
    private HttpResponse<String> upload(String path,String cookie,String key,String data)
            throws Exception {
        return uploadNamed(path,cookie,key,"meter-reading.csv",data);
    }
    private HttpResponse<String> uploadNamed(String path,String cookie,String key,
                    String name,String data) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("X-Source-Id",SOURCE).header("X-File-Name",name)
            .header("Idempotency-Key",key).header("Content-Type","text/csv");
        if(cookie!=null)builder.header("Cookie",cookie);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(data)).build(),
            HttpResponse.BodyHandlers.ofString());
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
    private JsonNode read(HttpResponse<String> r) throws Exception {
        return json.readTree(r.body());
    }
}
