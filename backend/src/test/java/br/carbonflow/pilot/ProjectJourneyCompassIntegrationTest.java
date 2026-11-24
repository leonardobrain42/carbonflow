package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.*;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.*;
import java.util.UUID;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** UX-07: visible task state, private resume pointers and revoked grants. */
@QuarkusTest
@QuarkusTestResource(value=AccountAccessTest.Inbox.class,restrictToAnnotatedClass=true)
class ProjectJourneyCompassIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}
    private static String key(){return UUID.randomUUID().toString();}

    @Test
    void projectScopedProgressResumeAndDeniedDestinationAreNotCertification() throws Exception {
        Actor owner=actor(),outsider=actor(),prep=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        String another=project(owner,org);
        String endpoint="/v1/projects/"+project+"/journey";
        var initial=call("GET",endpoint,owner.cookie(),null);
        assertEquals(200,initial.statusCode(),initial.body());
        JsonNode start=json(initial);
        assertEquals(project,start.path("projectId").asText());
        assertEquals("SANDBOX",start.path("environment").asText());
        assertTrue(start.path("progress").path("visible").asInt()>=2);
        assertEquals(0,start.path("progress").path("recorded").asInt());
        assertTrue(start.path("resume").isNull());
        assertTrue(start.path("recommendedTasks").isArray());
        assertTrue(start.path("nextTask").isTextual());
        assertFalse(start.has("normativelyApproved"));
        assertFalse(start.has("verifiedCredits"));

        assertEquals(401,call("GET",endpoint,null,null).statusCode());
        assertEquals(404,call("GET",endpoint,outsider.cookie(),null).statusCode());
        assertEquals(404,call("PUT",endpoint+"/resume",outsider.cookie(),
            "{\"area\":\"triage\",\"screen\":null,\"guide\":null,\"step\":null}").statusCode());
        String triage="{\"area\":\"triage\",\"screen\":null,\"guide\":null,\"step\":null}";
        var first=call("PUT",endpoint+"/resume",owner.cookie(),triage);
        assertEquals(200,first.statusCode(),first.body());
        assertEquals("triage",json(first).path("area").asText());
        assertTrue(first.headers().firstValue("Cache-Control").orElse("").contains("no-store"));
        assertEquals("triage",json(call("GET",endpoint,owner.cookie(),null))
            .path("resume").path("area").asText());
        assertTrue(json(call("GET","/v1/projects/"+another+"/journey",owner.cookie(),null))
            .path("resume").isNull());

        for(String rejected:new String[]{
            "{\"area\":\"technical\",\"screen\":\"sem-permissao\",\"guide\":null,\"step\":null}",
            "{\"area\":\"technical\",\"screen\":\"monitorar\",\"guide\":null,\"step\":\"admin\"}",
            "{\"area\":\"triage\",\"screen\":\"guias\",\"guide\":null,\"step\":null}",
            "{\"area\":\"technical\",\"screen\":\"linha-base\",\"guide\":null,\"step\":\"frota\"}",
            "{\"area\":\"technical\",\"screen\":\"redigir\",\"guide\":\"not-a-guide\",\"step\":null}"
        }) assertEquals(422,call("PUT",endpoint+"/resume",owner.cookie(),rejected).statusCode(),rejected);
        assertEquals("triage",json(call("GET",endpoint,owner.cookie(),null))
            .path("resume").path("area").asText());

        var intake=call("PUT","/v1/projects/"+project+"/technical/intake",owner.cookie(),"""
            {"activity":"Rede pública de recarga","country":"BR","state":"DF",
             "locality":"Brasília","candidateMethodology":"VM0038",
             "referenceActDate":"2027-09-27","notes":"Projeto em planejamento"}
            ""","0");
        assertEquals(200,intake.statusCode(),intake.body());
        String factors="{\"area\":\"technical\",\"screen\":\"linha-base\",\"guide\":null,\"step\":\"fatores\"}";
        assertEquals(200,call("PUT",endpoint+"/resume",owner.cookie(),factors).statusCode());
        var resumed=json(call("GET",endpoint,owner.cookie(),null));
        assertEquals("VM0038_V1_1",resumed.at("/journeyProfile/id").asText());
        assertEquals("linha-base",resumed.path("resume").path("screen").asText());
        assertEquals("fatores",resumed.path("resume").path("step").asText());
        assertEquals(1,resumed.path("progress").path("recorded").asInt(),
            "An intake revision is work recorded, never a methodology approval.");
        assertTrue(resumed.path("progress").path("visible").asInt()>=
            resumed.path("progress").path("recorded").asInt());
        assertTrue(resumed.path("tasks").isArray());
        assertEquals(1L,count(project,owner.id()),
            "One checkpoint per account and project; updating it never creates a task revision.");
    }

    @Test
    void personalCheckpointIsDeniedAfterGrantRevocationAndNeverChangesProject() throws Exception {
        Actor owner=actor(),prep=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        var intake=call("PUT","/v1/projects/"+project+"/technical/intake",owner.cookie(),"""
            {"activity":"Recarga rápida aberta","country":"BR","state":"DF",
             "locality":"Brasília","candidateMethodology":"VM0038",
             "referenceActDate":"2027-01-01","notes":"Rede sem EVR comprovada"}
            ""","0");
        assertEquals(200,intake.statusCode(),intake.body());
        UUID grant=UUID.randomUUID();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                (id,project_id,organization_id,environment,identity_id,granted_by,
                 role,activities,valid_until)
                VALUES(?,?,?,'SANDBOX',?,?,'PREP',ARRAY['MONITORING']::text[],
                  statement_timestamp()+interval '2 days')
                """,grant,project,org,prep.id(),owner.id());
            return null;
        });
        String endpoint="/v1/projects/"+project+"/journey";
        var collaborator=json(call("GET",endpoint,prep.cookie(),null));
        assertEquals("VM0038_V1_1",collaborator.at("/journeyProfile/id").asText());
        assertFalse(collaborator.has("intakeAnswers"),
            "A shared methodology profile must never include private intake answers.");
        assertEquals(200,call("GET",endpoint,prep.cookie(),null).statusCode());
        assertEquals(422,call("PUT",endpoint+"/resume",prep.cookie(),
            "{\"area\":\"triage\",\"screen\":null,\"guide\":null,\"step\":null}").statusCode());
        assertEquals(422,call("PUT",endpoint+"/resume",prep.cookie(),
            "{\"area\":\"technical\",\"screen\":\"preparar\",\"guide\":null,\"step\":null}").statusCode());
        String monitor="{\"area\":\"technical\",\"screen\":\"monitorar\",\"guide\":null,\"step\":\"qa\"}";
        assertEquals(200,call("PUT",endpoint+"/resume",prep.cookie(),monitor).statusCode());
        assertEquals("qa",json(call("GET",endpoint,prep.cookie(),null)).at("/resume/step").asText());
        assertTrue(json(call("GET",endpoint,owner.cookie(),null)).path("resume").isNull(),
            "The owner's personal resume cannot contain the preparer's checkpoint.");
        for (String screen:java.util.List.of("rede","direitos"))
            assertEquals(200,call("PUT",endpoint+"/resume",owner.cookie(),
                "{\"area\":\"technical\",\"screen\":\""+screen+"\",\"guide\":null,\"step\":null}").statusCode(),screen);
        assertEquals("direitos",json(call("GET",endpoint,owner.cookie(),null)).at("/resume/screen").asText());
        database.transaction(c->{
            update(c,"""
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=?
                WHERE id=?
                """,owner.id(),grant);
            return null;
        });
        assertEquals(404,call("GET",endpoint,prep.cookie(),null).statusCode());
        assertEquals(404,call("PUT",endpoint+"/resume",prep.cookie(),monitor).statusCode());
        assertEquals(1L,count(project,prep.id()),
            "Historical personal navigation is retained only as private metadata.");
    }

    private long count(String project,UUID actor){
        return database.read(c->first(c,"""
            SELECT count(*) FROM app_project_journey_checkpoint WHERE project_id=? AND actor_id=?
            """,r->r.getLong(1),project,actor));
    }
    private Actor actor(){
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?, 'Jornada UX','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c,"""
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner){
        UUID id=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES(?,'SANDBOX','Jornada UX')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });
        return id;
    }
    private String project(Actor owner,UUID org)throws Exception{
        var response=call("POST","/v1/projects",owner.cookie(),
            "{\"organizationId\":\""+org+"\",\"title\":\"Projeto para jornada\"}");
        assertEquals(201,response.statusCode(),response.body());
        return json(response).path("projectId").asText();
    }
    private HttpResponse<String> call(String method,String path,String cookie,String data)throws Exception{
        return call(method,path,cookie,data,null);
    }
    private HttpResponse<String> call(String method,String path,String cookie,String data,String match)throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(data!=null)builder.header("Content-Type","application/json");
        if(match!=null)builder.header("If-Match",'"'+match+'"');
        if("POST".equals(method)&&path.equals("/v1/projects"))
            builder.header("Idempotency-Key",key());
        if("PUT".equals(method)&&path.endsWith("/technical/intake"))
            builder.header("Idempotency-Key",key());
        return client.send(builder.method(method,data==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
}
