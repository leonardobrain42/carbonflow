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
class OpenMeasurementReviewQaIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;
    private final HttpClient http=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
    private static final String CHARGER="""
        {"siteCode":"SITE-1","chargerKind":"DCFC","ratedPowerKw":150,
         "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
         "sourceReference":"COMMISSION-123"}
        """;
    private static String meter(String kind){
        return """
          {"chargerCode":"DCFC-1","energyKind":"%s","meterPosition":"%s",
           "sourceSystem":"METER-API-1","calibrationEvidenceRef":"CAL-2027-01",
           "calibrationValidFrom":"2027-01-01T00:00:00Z",
           "calibrationValidUntil":"2028-01-01T00:00:00Z",
           "configuredFrom":"2027-01-01T00:00:00Z",
           "configuredUntil":"2028-01-01T00:00:00Z","timeZone":"America/Sao_Paulo"}
          """.formatted(kind,kind.equals("ED")?"DOWNSTREAM":"UPSTREAM");
    }
    private static final String HEADER="meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n";
    private static final String DATA=HEADER+
        "ED-1,SEQ-1,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2700,kWh\n"+
        "EC-1,SEQ-1,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,3000,kWh\n";

    @Test
    void reviewerConfirmsRowsThenCreatesQaOnlySnapshotWithVersionPinned() throws Exception {
        Actor owner=actor(),operator=actor(),reviewer=actor(),outsider=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org),other=project(outsider,organization(outsider.id()));
        configure(owner,project);
        grant(project,org,owner.id(),operator.id(),"OP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        JsonNode lot=upload(root+"/batches",operator.cookie(),DATA);
        assertEquals("CANDIDATE",lot.path("status").asText());
        String batch=lot.path("id").asText();
        String reviews=root+"/batches/"+batch+"/reviews";
        String period=root+"/period-qa?from=2027-02-01T00%3A00%3A00Z&until=2027-02-01T01%3A00%3A00Z";

        assertEquals(404,call("POST",reviews.replace("/reviews","")+
            "/rows/2/reviews",owner.cookie(),"\"0\"",
            "{\"outcome\":\"ACCEPTED_FOR_QA\",\"reason\":\"Aceite independente de medição do intervalo.\"}").statusCode());
        assertEquals(404,call("POST",reviews.replace("/reviews","")+
            "/rows/2/reviews",operator.cookie(),"\"0\"",
            "{\"outcome\":\"ACCEPTED_FOR_QA\",\"reason\":\"Aceite independente de medição do intervalo.\"}").statusCode());
        assertEquals(404,call("GET","/v1/projects/"+other+
            "/technical/open-monitoring/batches/"+batch+"/reviews",outsider.cookie(),
            null,null).statusCode());

        JsonNode preliminary=read(call("GET",period,owner.cookie(),null,null));
        assertEquals("BLOCKED_QA",preliminary.path("state").asText());
        assertTrue(preliminary.path("edKwh").isNull());
        assertTrue(preliminary.path("gates").toString().contains("REVIEW_MISSING"));
        assertFalse(preliminary.path("canRunE8").asBoolean());

        String accept="{\"outcome\":\"ACCEPTED_FOR_QA\","+
           "\"reason\":\"Revisão técnica interna com comprovante de calibração e fronteira.\"}";
        for(int line=2;line<=3;line++){
            String path=root+"/batches/"+batch+"/rows/"+line+"/reviews";
            HttpResponse<String> response=call("POST",path,reviewer.cookie(),"\"0\"",accept);
            assertEquals(200,response.statusCode(),response.body());
            assertEquals(1,read(response).path("version").asInt());
            assertEquals("\"1\"",response.headers().firstValue("ETag").orElseThrow());
            assertEquals(read(response),read(call("POST",path,reviewer.cookie(),"\"0\"",accept)));
        }
        assertEquals(2,read(call("GET",reviews,owner.cookie(),null,null)).size());
        JsonNode qa=read(call("GET",period,owner.cookie(),null,null));
        assertEquals("READY_FOR_INTERNAL_QA_SNAPSHOT",qa.path("state").asText(),qa.toString());
        assertTrue(qa.path("readyForInternalSnapshot").asBoolean());
        assertEquals(0,new java.math.BigDecimal(qa.path("edKwh").asText())
          .compareTo(new java.math.BigDecimal("2700")));
        assertEquals(0,new java.math.BigDecimal(qa.path("ecKwh").asText())
          .compareTo(new java.math.BigDecimal("3000")));
        assertFalse(qa.path("canRunE8").asBoolean());
        String snapshotUrl=root+"/period-qa/snapshots";
        String range="{\"from\":\"2027-02-01T00:00:00Z\",\"until\":\"2027-02-01T01:00:00Z\"}";
        assertEquals(404,call("POST",snapshotUrl,owner.cookie(),null,range).statusCode());
        HttpResponse<String> created=call("POST",snapshotUrl,reviewer.cookie(),null,range);
        assertEquals(201,created.statusCode(),created.body());
        JsonNode snapshot=read(created);
        assertEquals("INTERNAL_QA_ONLY",snapshot.path("status").asText());
        assertFalse(snapshot.path("usableForE8").asBoolean());
        assertEquals(64,snapshot.path("sourceSha256").asText().length());
        assertEquals(snapshot,read(call("POST",snapshotUrl,reviewer.cookie(),null,range)));
        assertEquals(1,read(call("GET",snapshotUrl,owner.cookie(),null,null)).size());
        assertEquals(2L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_period_qa_snapshot_line WHERE project_id=?",
            r->r.getLong(1),project)).longValue());
        assertEquals(0L,database.read(c->first(c,
            "SELECT count(*) FROM pilot_selected_run WHERE project_id=?",
            r->r.getLong(1),project)).longValue());

        // The decision ledger can be superseded, never overwritten. Historical
        // snapshot remains pinned at version 1, but a new preview becomes blocked.
        String rejected="{\"outcome\":\"REJECTED\",\"reason\":\"Inconsistência documental posterior: nova verificação obrigatória.\"}";
        var superseded=call("POST",root+"/batches/"+batch+"/rows/2/reviews",
            reviewer.cookie(),"\"1\"",rejected);
        assertEquals(200,superseded.statusCode(),superseded.body());
        assertEquals(2,read(superseded).path("version").asInt());
        assertEquals("BLOCKED_QA",read(call("GET",period,reviewer.cookie(),null,null))
            .path("state").asText());
        assertEquals(409,call("POST",snapshotUrl,reviewer.cookie(),null,range).statusCode());
        assertEquals(1,read(call("GET",snapshotUrl,reviewer.cookie(),null,null)).size());
    }

    @Test
    void invalidQuarantineAndSelfReviewCannotBeQaAccepted() throws Exception {
        Actor owner=actor(),operator=actor(),reviewer=actor();
        UUID org=organization(owner.id());String project=project(owner,org);
        configure(owner,project);
        grant(project,org,owner.id(),operator.id(),"OP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        String invalid=DATA.replace("2700","0");
        String batch=upload(root+"/batches",operator.cookie(),invalid).path("id").asText();
        String url=root+"/batches/"+batch+"/rows/2/reviews";
        String yes="{\"outcome\":\"ACCEPTED_FOR_QA\","+
            "\"reason\":\"Parecer interno de QA independente com certificado.\"}";
        assertEquals(409,call("POST",url,reviewer.cookie(),"\"0\"",yes).statusCode());
        String rejected="{\"outcome\":\"REJECTED\",\"reason\":\"Quarentena por valor zero sem evidência externa suficiente.\"}";
        assertEquals(200,call("POST",url,reviewer.cookie(),"\"0\"",rejected).statusCode());
        assertEquals(412,call("POST",url,reviewer.cookie(),"\"0\"",yes).statusCode());
        assertEquals(412,call("POST",url,reviewer.cookie(),"\"0\"",rejected.replace("zero","nulo")).statusCode());
        assertEquals(409,call("POST",url,reviewer.cookie(),"\"1\"",yes).statusCode());
        // Even if account changes role to REV later, it cannot review its own
        // earlier OP-submitted batch.
        database.transaction(c->{
            update(c,"""
              UPDATE app_project_collaborator_grant
              SET revoked_at=statement_timestamp(),revoked_by=?
              WHERE project_id=? AND identity_id=? AND role='OP' AND revoked_at IS NULL
              """,owner.id(),project,operator.id());
            return null;
        });
        grant(project,org,owner.id(),operator.id(),"REV");
        assertEquals(403,call("POST",root+"/batches/"+batch+"/rows/3/reviews",
            operator.cookie(),"\"0\"",rejected).statusCode());
    }

    private void configure(Actor owner,String project) throws Exception {
        String base="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(200,call("PUT",base+"/chargers/DCFC-1",owner.cookie(),"\"0\"",CHARGER).statusCode());
        assertEquals(200,call("PUT",base+"/meters/ED-1",owner.cookie(),"\"0\"",meter("ED")).statusCode());
        assertEquals(200,call("PUT",base+"/meters/EC-1",owner.cookie(),"\"0\"",meter("EC")).statusCode());
    }
    private JsonNode upload(String path,String cookie,String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","text/csv")
            .header("X-Source-Id","METER-API-1").header("X-File-Name","qa-readings.csv")
            .header("Idempotency-Key",key())
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,response.statusCode(),response.body());
        return read(response);
    }
    private HttpResponse<String> call(String method,String path,String cookie,String ifMatch,String body)
        throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(ifMatch!=null)builder.header("If-Match",ifMatch);
        if(body!=null)builder.header("Content-Type","application/json");
        if("PUT".equals(method))builder.header("Idempotency-Key",key());
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode read(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }
    private void grant(String project,UUID org,UUID owner,UUID identity,String role) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                 (id,project_id,organization_id,environment,identity_id,granted_by,
                  role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,?,ARRAY['MONITORING']::text[],
                  statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,identity,owner,role);
            return null;
        });
    }
    private Actor actor() {
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Review QA Test','ACTIVE',statement_timestamp())
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
        UUID org=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','QA medidores')",org);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",org,owner);
            return null;
        });
        return org;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve("/v1/projects"))
            .header("Cookie",owner.cookie()).header("Content-Type","application/json")
            .header("Idempotency-Key",key())
            .POST(HttpRequest.BodyPublishers.ofString(
                "{\"organizationId\":\""+org+"\",\"title\":\"OPEN QA período\"}")).build();
        var response=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());
        return read(response).path("projectId").asText();
    }
}
