package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.Set;
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
class OpenApplicabilityIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();
    private record Actor(UUID id,String cookie) {}

    @Test
    void projectCanPrepareOpenApplicabilityWithoutRealSourceAndNeverReceivesE8Admission() throws Exception {
        Actor owner=actor(),outsider=actor();
        String id=project(owner,organization(owner.id()));
        String path="/v1/projects/"+id+"/technical/open-applicability";
        assertEquals(401,call("GET",path,null,null,null,null).statusCode());
        assertEquals(404,call("GET",path,outsider.cookie(),null,null,null).statusCode());
        JsonNode initial=json(call("GET",path,owner.cookie(),null,null,null));
        assertEquals(0,initial.path("version").asInt());
        assertTrue(initial.at("/readiness/canContinuePreparation").asBoolean());
        assertFalse(initial.at("/readiness/canUseForE8").asBoolean());
        assertTrue(initial.at("/readiness/sourceDependencies").isArray());

        String draft="""
                {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
                 "privateLand":true,"chargerType":"DCFC","propulsion":"BEV",
                 "electricitySource":"SIN","associatedInfrastructure":false,"v2g":false,
                 "chargingOwner":"Titular do carregador","carbonRightsContractRef":"CONTRACT-001",
                 "driverDisclosureChannel":"NOT_DECLARED","doubleClaimingCheck":"UNKNOWN",
                 "effectiveFrom":"2027-02-01","explanation":"Preparação sem fonte real ainda"}
                """;
        String key=UUID.randomUUID().toString();
        var first=call("PUT",path,owner.cookie(),key,"\"0\"",draft);
        assertEquals(200,first.statusCode(),first.body());
        var saved=json(first);
        assertEquals(1,saved.path("version").asInt());
        assertEquals("OPEN_CANDIDATE",saved.at("/readiness/network").asText());
        assertEquals("EVIDENCE_PENDING",saved.at("/readiness/rights").asText());
        assertEquals("PENDING_NORMATIVE_REVIEW",saved.at("/readiness/norm").asText());
        assertFalse(saved.at("/readiness/canUseForE8").asBoolean());
        assertTrue(saved.at("/readiness/findings").toString().contains("DRIVER_DISCLOSURE"));
        assertEquals(1L,count(id));
        assertEquals(200,call("PUT",path,owner.cookie(),key,"\"0\"",draft).statusCode());
        assertEquals(1L,count(id));
        assertEquals(409,call("PUT",path,owner.cookie(),key,"\"0\"",
            draft.replace("true","false")).statusCode());
        assertEquals(412,call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),
            "\"0\"",draft).statusCode());
        assertEquals(428,call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),
            null,draft).statusCode());

        String withDisclosure="""
                {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
                 "privateLand":true,"chargerType":"DCFC","propulsion":"BEV",
                 "electricitySource":"SIN","associatedInfrastructure":false,"v2g":false,
                 "chargingOwner":"Titular do carregador","carbonRightsContractRef":"CONTRACT-001",
                 "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-2027-01",
                 "driverDisclosureEffectiveOn":"2027-01-15",
                 "doubleClaimingCheck":"NO_OVERLAP_DECLARED",
                 "effectiveFrom":"2027-02-01"}
                """;
        var updated=call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),"\"1\"",withDisclosure);
        assertEquals(200,updated.statusCode(),updated.body());
        JsonNode improved=json(updated);
        assertEquals(2,improved.path("version").asInt());
        assertEquals("DECLARED_REVIEW_PENDING",improved.at("/readiness/rights").asText());
        assertFalse(improved.at("/readiness/canUseForE8").asBoolean());

        // Reclassify without VIN: private land alone does not make network CLOSED.
        assertEquals("OPEN_CANDIDATE",improved.at("/readiness/network").asText());
        String triage="/v1/projects/"+id+"/triage";
        assertEquals(200,call("PUT",triage+"/steps/1",owner.cookie(),
            UUID.randomUUID().toString(),"\"0\"",
            "{\"answers\":{\"objective\":\"Planejar rede\",\"activityType\":\"MOBILIDADE_ELETRICA\",\"methodologyCandidate\":\"VM0038\"},\"nextStep\":2}"
            ).statusCode());
        JsonNode stale=json(call("GET",path,owner.cookie(),null,null,null));
        assertTrue(stale.at("/readiness/stale").asBoolean());
        assertTrue(stale.at("/readiness/findings").toString().contains("TRIAGE_CHANGED"));
        assertEquals(2L,count(id));
        assertEquals(0,database.read(c->first(c,
            "SELECT count(*) FROM pilot_intake_revision WHERE project_id=?",
            r->r.getInt(1),id)).intValue());
    }

    @Test
    void closedFleetPhevAndV2gCannotBePromotedAndUnknownDoesNotBecomeZero() {
        var restricted=OpenApplicability.evaluate(new OpenApplicability.Input(false,true,true,
            "DCFC","PHEV","SIN",false,true,null,null,"NOT_DECLARED",null,null,
            "UNKNOWN",null,null),false);
        assertEquals("CLOSED_RESTRICTION",restricted.network());
        assertEquals("OUTSIDE_FIRST_IMPLEMENTED_SCOPE",restricted.category());
        assertFalse(restricted.canUseForE8());
        assertTrue(restricted.findings().stream().anyMatch(f->"CLOSED_NETWORK".equals(f.code())));
        assertTrue(restricted.findings().stream().noneMatch(f->Set.of(
            "MARKET_ROUTE","VMD0049_ROUTE","REGULATORY_SURPLUS").contains(f.code())));
        assertFalse(restricted.canUseForE8());
    }

    private Actor actor() {
        UUID id=UUID.randomUUID();
        String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Aplicabilidade teste','ACTIVE',statement_timestamp())
                """,id,id+"@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return new Actor(id,AccountAccess.COOKIE+"="+token);
    }
    private UUID organization(UUID owner) {
        UUID id=UUID.randomUUID();
        database.transaction(c->{
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Rede aberta teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });
        return id;
    }
    private String project(Actor owner,UUID org) throws Exception {
        var result=call("POST","/v1/projects",owner.cookie(),UUID.randomUUID().toString(),null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Projeto OPEN teste\"}");
        assertEquals(201,result.statusCode(),result.body());
        return json(result).path("projectId").asText();
    }
    private long count(String project){
        return database.read(c->first(c,
          "SELECT count(*) FROM app_project_open_applicability_revision WHERE project_id=?",
          r->r.getLong(1),project));
    }
    private HttpResponse<String> call(String method,String path,String cookie,String key,String etag,String payload)throws Exception {
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)request.header("Cookie",cookie);
        if(key!=null)request.header("Idempotency-Key",key);
        if(etag!=null)request.header("If-Match",etag);
        if(payload!=null)request.header("Content-Type","application/json");
        return client.send(request.method(method,payload==null?HttpRequest.BodyPublishers.noBody():
            HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response)throws Exception {
        return mapper.readTree(response.body());
    }
}
