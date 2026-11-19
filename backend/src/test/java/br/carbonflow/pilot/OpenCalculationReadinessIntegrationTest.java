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
class OpenCalculationReadinessIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();

    private record Actor(UUID id, String cookie) {}

    @Test
    void projectReadinessIsPrivateAndAlwaysRejectsSimulatedInputs() throws Exception {
        Actor owner=actor(),outsider=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        String path="/v1/projects/"+project+"/technical/open-calculation-readiness";

        assertEquals(401,call("GET",path,null,null,null,null).statusCode());
        assertEquals(404,call("GET",path,outsider.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET","/v1/projects/PIL-SYN-BR-001/technical/open-calculation-readiness",
            owner.cookie(),null,null,null).statusCode());
        var initial=call("GET",path,owner.cookie(),null,null,null);
        assertEquals(200,initial.statusCode(),initial.body());
        assertEquals("private, no-store",initial.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals("BLOCKED_NOT_ADMISSIBLE",json(initial).path("calculationState").asText());
        assertNull(json(initial).at("/emissions/erTco2e").textValue());

        String intake="/v1/projects/"+project+"/technical/intake";
        String intakeInput="""
            {"activity":"Recarga de veículos elétricos","country":"BR",
             "candidateMethodology":"VM0038"}
            """;
        assertEquals(200,call("PUT",intake,owner.cookie(),key(),"\"0\"",intakeInput).statusCode());

        String applicability="/v1/projects/"+project+"/technical/open-applicability";
        String open="""
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "chargerType":"DCFC","propulsion":"BEV","electricitySource":"SIN",
             "associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Proprietário","carbonRightsContractRef":"CONTRACT-12",
             "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-12",
             "driverDisclosureEffectiveOn":"2027-01-01",
             "doubleClaimingCheck":"NO_OVERLAP_DECLARED","effectiveFrom":"2027-02-01"}
            """;
        assertEquals(200,call("PUT",applicability,owner.cookie(),key(),"\"0\"",open).statusCode());
        var ready=call("GET",path,owner.cookie(),null,null,null);
        assertEquals(200,ready.statusCode(),ready.body());
        JsonNode data=json(ready);
        assertEquals("VM0038",data.path("methodology").asText());
        assertEquals("BLOCKED_MISSING_DATA",data.path("calculationState").asText());
        assertTrue(data.path("canContinuePreparation").asBoolean());
        assertFalse(data.path("canRunE8").asBoolean());
        assertFalse(data.path("canIssueVcu").asBoolean());
        assertNull(data.at("/emissions/beTco2e").textValue());
        assertNull(data.at("/emissions/peTco2e").textValue());
        assertNull(data.at("/emissions/erTco2e").textValue());
        assertTrue(data.path("gates").toString().contains("PENDING_MEASUREMENT"));
        assertTrue(data.path("gates").toString().contains("MISSING_BINDING"));
        assertFalse(data.toString().contains("EF-SYN-v1"));
        assertFalse(data.toString().contains("SIM-DY"));
        assertEquals(0L,database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_calculation WHERE project_id = ?",
            r -> r.getLong(1),project)).longValue());
    }

    @Test
    void scopedCollaboratorOnlyReadsWithActiveGrantAndRevocationIsImmediate() throws Exception {
        Actor owner=actor(),preparer=actor(),outsider=actor();
        UUID org=organization(owner.id());
        String project=project(owner,org);
        String path="/v1/projects/"+project+"/technical/open-calculation-readiness";
        assertEquals(404,call("GET",path,preparer.cookie(),null,null,null).statusCode());
        UUID grant=UUID.randomUUID();
        database.transaction(c -> {
            update(c,"""
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['CALCULATION']::text[],
                   statement_timestamp()+interval '3 days')
                """,grant,project,org,preparer.id(),owner.id());
            return null;
        });
        assertEquals(200,call("GET",path,preparer.cookie(),null,null,null).statusCode());
        assertEquals(404,call("GET",path,outsider.cookie(),null,null,null).statusCode());
        database.transaction(c -> {
            update(c,"""
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=?
                WHERE id=?
                """,owner.id(),grant);
            return null;
        });
        assertEquals(404,call("GET",path,preparer.cookie(),null,null,null).statusCode());
        assertTrue(database.read(c -> first(c,"""
            SELECT count(*) FROM app_project_technical_access_event
            WHERE actor_id=? AND target_digest=? AND action='VIEW_OPEN_CALCULATION'
              AND outcome='DENIED'
            """,r->r.getLong(1),preparer.id(),AccessSecrets.digest(project))) >= 1L);
    }

    private Actor actor() {
        UUID id=UUID.randomUUID();
        String token=AccessSecrets.newToken();
        database.transaction(c -> {
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Pré-cálculo teste','ACTIVE',statement_timestamp())
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
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','E8 dinâmico teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });
        return id;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var result=call("POST","/v1/projects",owner.cookie(),key(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Rede OPEN para cálculo\"}");
        assertEquals(201,result.statusCode(),result.body());
        return json(result).path("projectId").asText();
    }
    private static String key() { return UUID.randomUUID().toString(); }
    private HttpResponse<String> call(String method,String path,String cookie,String key,
            String ifMatch,String body) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(ifMatch!=null)builder.header("If-Match",ifMatch);
        if(body!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,body==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
