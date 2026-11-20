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
class OpenPeriodSupportIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient http=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}
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
    private static final String DATA=
        "meterCode,sequence,kind,start,endExclusive,zone,quantity,unit\n"+
        "ED-1,SEQ-1,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2700,kWh\n"+
        "EC-1,SEQ-1,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,3000,kWh\n";
    private static final String FROM="2027-02-01T00:00:00Z",TO="2027-02-01T01:00:00Z";
    private static final String ACCEPTED="{\"outcome\":\"DOCUMENT_CHECKED_INTERNAL\","+
        "\"reason\":\"Conferência técnica interna do documento e da identidade do medidor.\"}";

    @Test
    void proofFilesAreEncryptedReviewedIndependentlyAndSnapshotTurnsStale() throws Exception {
        Actor owner=actor(),op=actor(),qaReviewer=actor(),docsReviewer=actor(),outside=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        configure(owner,project);
        grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),qaReviewer.id(),"REV");
        grant(project,org,owner.id(),docsReviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        JsonNode batch=uploadCsv(root+"/batches",op,DATA);
        String id=batch.path("id").asText();
        for(int line: new int[]{2,3}) {
            var response=call("POST",root+"/batches/"+id+"/rows/"+line+"/reviews",
                qaReviewer.cookie(),"\"0\"", """
                {"outcome":"ACCEPTED_FOR_QA",
                 "reason":"Leitura tecnicamente consistente para conciliação horária."}
                """);
            assertEquals(200,response.statusCode(),response.body());
        }
        String snapshots=root+"/period-qa/snapshots";
        String period="{\"from\":\""+FROM+"\",\"until\":\""+TO+"\"}";
        var created=call("POST",snapshots,qaReviewer.cookie(),null,period);
        assertEquals(201,created.statusCode(),created.body());
        String snapshot=json(created).path("id").asText();
        String admission=snapshots+"/"+snapshot+"/admissibility";
        JsonNode initial=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("PENDING_DOCUMENTARY_REVIEW",initial.path("state").asText());
        assertFalse(initial.path("documentaryComplete").asBoolean());
        assertFalse(initial.path("usableForE8").asBoolean());
        assertEquals(0,new java.math.BigDecimal(initial.path("historicalEdKwh").asText()).compareTo(
            new java.math.BigDecimal("2700")));
        assertTrue(initial.path("gates").toString().contains("SUPPORT_DOCUMENT_MISSING"));

        assertEquals(404,call("GET",admission,outside.cookie(),null,null).statusCode());
        String proofPath=snapshots+"/"+snapshot+"/support";
        String pdf="%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n";
        String[][] subjects={{"METER_CALIBRATION","ED-1"},{"METER_CALIBRATION","EC-1"},
            {"SOURCE_RIGHTS","METER-API-1"}};
        for(int i=0;i<subjects.length;i++) {
            String requestKey=key();
            String payload=pdf+"%"+i+"\n";
            var result=uploadPdf(proofPath,op.cookie(),subjects[i][0],subjects[i][1],
                requestKey,payload);
            assertEquals(201,result.statusCode(),result.body());
            var proof=json(result);
            assertEquals(subjects[i][0],proof.path("kind").asText());
            assertEquals(64,proof.path("sha256").asText().length());
            assertFalse(result.body().contains("encrypted"));
            assertEquals(proof,json(uploadPdf(proofPath,op.cookie(),subjects[i][0],
                subjects[i][1],requestKey,payload)));
            String reviewUrl=root+"/period-qa/support/"+proof.path("id").asText()+"/reviews";
            assertEquals(404,call("POST",reviewUrl,owner.cookie(),"\"0\"",ACCEPTED).statusCode());
            assertEquals(404,call("POST",reviewUrl,op.cookie(),"\"0\"",ACCEPTED).statusCode());
            assertEquals(403,call("POST",reviewUrl,qaReviewer.cookie(),"\"0\"",ACCEPTED).statusCode());
            var outcome=call("POST",reviewUrl,docsReviewer.cookie(),"\"0\"",ACCEPTED);
            assertEquals(200,outcome.statusCode(),outcome.body());
            assertEquals(1,json(outcome).path("version").asInt());
            assertEquals("\"1\"",outcome.headers().firstValue("ETag").orElseThrow());
        }
        var valid=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("READY_FOR_NORMATIVE_REVIEW",valid.path("state").asText(),valid.toString());
        assertTrue(valid.path("documentaryComplete").asBoolean());
        assertFalse(valid.path("stale").asBoolean());
        assertFalse(valid.path("usableForE8").asBoolean());
        assertEquals(3,valid.path("documents").size());
        assertEquals(0,valid.path("gates").size());

        long[] evidence=database.read(c->new long[]{
            first(c,"SELECT count(*) FROM app_project_evidence_blob WHERE project_id=? AND mime_type='application/pdf'",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM app_open_period_support_review WHERE project_id=?",
                r->r.getLong(1),project),
            first(c,"SELECT count(*) FROM pilot_import_file WHERE project_id=?",
                r->r.getLong(1),project)
        });
        assertArrayEquals(new long[]{3,3,0},evidence);
        byte[] ciphertext=database.read(c->first(c,"""
            SELECT encrypted FROM app_project_evidence_blob
            WHERE project_id=? AND mime_type='application/pdf' LIMIT 1
            """,r->r.getBytes(1),project));
        assertFalse(new String(ciphertext,StandardCharsets.ISO_8859_1).contains("%PDF-"));

        // Operational source interruption reported after a frozen QA snapshot
        // must immediately invalidate its current admissibility. Review outcome
        // never mutates the historical snapshot or implies E8 admission.
        String events=root+"/events";
        String occurrence="""
            {"kind":"SOURCE_OUTAGE","subjectType":"SOURCE",
             "subjectCode":"METER-API-1",
             "from":"2027-02-01T00:20:00Z","until":"2027-02-01T00:40:00Z",
             "incidentReference":"OUTAGE-2027-001",
             "description":"Interrupção do provedor operacional durante o intervalo medido."}
            """;
        String eventKey=key();
        var reported=postEvent(events,op.cookie(),eventKey,occurrence);
        assertEquals(201,reported.statusCode(),reported.body());
        String eventId=json(reported).path("id").asText();
        assertEquals(eventId,json(postEvent(events,op.cookie(),eventKey,occurrence))
            .path("id").asText());
        assertEquals(409,postEvent(events,op.cookie(),eventKey,
            occurrence.replace("OUTAGE-2027-001","OUTAGE-2027-002")).statusCode());
        assertEquals(404,call("GET",events,outside.cookie(),null,null).statusCode());
        assertEquals(404,call("POST",events+"/"+eventId+"/reviews",
            op.cookie(),"\"0\"", """
            {"outcome":"CLEARED_FOR_INTERNAL_QA",
             "reason":"A origem confirmou a janela e comprovou reprocessamento integral.",
             "evidenceReference":"REPROCESS-2027-001"}
            """).statusCode());
        assertTrue(json(call("GET",root+
            "/period-qa?from=2027-02-01T00%3A00%3A00Z&until=2027-02-01T01%3A00%3A00Z",
            owner.cookie(),null,null)).path("gates").toString().contains("OPERATIONAL_SOURCE_OUTAGE"));
        assertEquals("BLOCKED_STALE",json(call("GET",admission,owner.cookie(),null,null))
            .path("state").asText());
        String clearOccurrence="""
            {"outcome":"CLEARED_FOR_INTERNAL_QA",
             "reason":"A origem confirmou a janela e comprovou reprocessamento integral.",
             "evidenceReference":"REPROCESS-2027-001"}
            """;
        var cleared=call("POST",events+"/"+eventId+"/reviews",docsReviewer.cookie(),
            "\"0\"",clearOccurrence);
        assertEquals(200,cleared.statusCode(),cleared.body());
        assertEquals("\"1\"",cleared.headers().firstValue("ETag").orElseThrow());
        assertEquals(1,json(cleared).path("version").asInt());
        assertEquals("READY_FOR_INTERNAL_QA_SNAPSHOT",json(call("GET",root+
            "/period-qa?from=2027-02-01T00%3A00%3A00Z&until=2027-02-01T01%3A00%3A00Z",
            owner.cookie(),null,null)).path("state").asText());
        // Clearance changes SHA lineage; the old snapshot never resurrects.
        JsonNode oldSnapshot=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("BLOCKED_STALE",oldSnapshot.path("state").asText());
        assertTrue(oldSnapshot.path("gates").toString().contains("SNAPSHOT_REVISIONS_CHANGED"));
        assertFalse(oldSnapshot.path("usableForE8").asBoolean());
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_open_operational_event WHERE project_id=?",
            r->r.getLong(1),project)).longValue());

        String rejected="{\"outcome\":\"REJECTED\",\"reason\":\"Amostra não validada; outra prova necessária para manter a série.\"}";
        var change=call("POST",root+"/batches/"+id+"/rows/2/reviews",
            qaReviewer.cookie(),"\"1\"",rejected);
        assertEquals(200,change.statusCode(),change.body());
        JsonNode stale=json(call("GET",admission,owner.cookie(),null,null));
        assertEquals("BLOCKED_STALE",stale.path("state").asText());
        assertTrue(stale.path("stale").asBoolean());
        assertTrue(stale.path("gates").toString().contains("SNAPSHOT_QA_STALE"));
        assertFalse(stale.path("usableForE8").asBoolean());
        assertEquals("INTERNAL_QA_ONLY",json(call("GET",snapshots,owner.cookie(),null,null))
            .get(0).path("status").asText());
    }

    @Test
    void proofUploadDoesNotAutomaticallyVerifyRightsAndInvalidPdfFailsClosed() throws Exception {
        Actor owner=actor(),op=actor(),reviewer=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        configure(owner,project);
        grant(project,org,owner.id(),op.id(),"OP");
        grant(project,org,owner.id(),reviewer.id(),"REV");
        String root="/v1/projects/"+project+"/technical/open-monitoring";
        String batch=uploadCsv(root+"/batches",op,DATA).path("id").asText();
        for(int line:new int[]{2,3}) {
            assertEquals(200,call("POST",root+"/batches/"+batch+"/rows/"+line+"/reviews",
                reviewer.cookie(),"\"0\"", """
                {"outcome":"ACCEPTED_FOR_QA","reason":"Leitura consistente para QA interno."}
                """).statusCode());
        }
        String snapshot=json(call("POST",root+"/period-qa/snapshots",reviewer.cookie(),null,
            "{\"from\":\""+FROM+"\",\"until\":\""+TO+"\"}")).path("id").asText();
        String path=root+"/period-qa/snapshots/"+snapshot+"/support";
        assertEquals(422,uploadPdf(path,op.cookie(),"METER_CALIBRATION","ED-1",
            key(),"some plain text, not a PDF certificate").statusCode());
        assertEquals(409,uploadPdf(path,op.cookie(),"METER_CALIBRATION","NOT-A-METER",
            key(),"%PDF-1.4\n1 0 obj\nendobj\n%%EOF").statusCode());
        assertEquals(404,uploadPdf(path,reviewer.cookie(),"METER_CALIBRATION","ED-1",
            key(),"%PDF-1.4\n1 0 obj\nendobj\n%%EOF").statusCode());
        assertEquals(1L,database.read(c->first(c,
            "SELECT count(*) FROM app_project_evidence_blob WHERE project_id=?",
            r->r.getLong(1),project)).longValue()); // original CSV only
        assertFalse(json(call("GET",root+"/period-qa/snapshots/"+snapshot+"/admissibility",
            owner.cookie(),null,null)).path("documentaryComplete").asBoolean());
    }

    private void configure(Actor owner,String project) throws Exception {
        String base="/v1/projects/"+project+"/technical/open-monitoring";
        assertEquals(200,call("PUT",base+"/chargers/DCFC-1",owner.cookie(),null,CHARGER).statusCode());
        for(String kind:new String[]{"ED","EC"}) {
            assertEquals(200,call("PUT",base+"/meters/"+kind+"-1",
                owner.cookie(),null,meter(kind)).statusCode());
        }
    }
    private JsonNode uploadCsv(String path,Actor actor,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",actor.cookie()).header("Content-Type","text/csv")
            .header("X-Source-Id","METER-API-1").header("X-File-Name","meter-qa.csv")
            .header("Idempotency-Key",key())
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        var result=http.send(request,HttpResponse.BodyHandlers.ofString());
        assertEquals(202,result.statusCode(),result.body());
        return json(result);
    }
    private HttpResponse<String> uploadPdf(String path,String cookie,String kind,
                            String subject,String key,String payload) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/pdf")
            .header("X-Evidence-Kind",kind).header("X-Subject-Code",subject)
            .header("X-Evidence-Reference","Documento de suporte verificado #1")
            .header("X-File-Name","support-evidence.pdf").header("Idempotency-Key",key)
            .POST(HttpRequest.BodyPublishers.ofString(payload)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> postEvent(String path,String cookie,String idempotency,
                                            String body) throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
            .header("Cookie",cookie).header("Content-Type","application/json")
            .header("Idempotency-Key",idempotency)
            .POST(HttpRequest.BodyPublishers.ofString(body)).build();
        return http.send(request,HttpResponse.BodyHandlers.ofString());
    }
    private HttpResponse<String> call(String method,String path,String cookie,
                    String match,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(body!=null)builder.header("Content-Type","application/json");
        if(match!=null)builder.header("If-Match",match);
        if("PUT".equals(method)) {
            builder.header("If-Match","\"0\"");
            builder.header("Idempotency-Key",key());
        }
        return http.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
    private Actor actor() {
        UUID id=UUID.randomUUID();
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'QA documentary','ACTIVE',statement_timestamp())
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
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Docs carbon')",org);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",org,owner);
            return null;
        });
        return org;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var req=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve("/v1/projects"))
            .header("Cookie",owner.cookie()).header("Content-Type","application/json")
            .header("Idempotency-Key",key()).POST(HttpRequest.BodyPublishers.ofString(
                "{\"organizationId\":\""+org+"\",\"title\":\"Documents QA\"}")).build();
        var result=http.send(req,HttpResponse.BodyHandlers.ofString());
        assertEquals(201,result.statusCode(),result.body());
        return json(result).path("projectId").asText();
    }
    private void grant(String project,UUID org,UUID owner,UUID actor,String role) {
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                (id,project_id,organization_id,environment,identity_id,granted_by,
                 role,activities,valid_until)
                VALUES (?,?,?,'SANDBOX',?,?,?,ARRAY['MONITORING']::text[],
                        statement_timestamp()+interval '3 days')
                """,UUID.randomUUID(),project,org,actor,owner,role);
            return null;
        });
    }
}
