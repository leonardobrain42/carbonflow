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
        // D17/AGN-01: the OPEN declaration needs VM0038 as the candidate; without it, saving is refused.
        assertEquals(409,call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),"\"0\"",
            "{\"unrestrictedBevAccess\":true,\"restrictedToIdentifiedVehicles\":false}").statusCode());
        assertEquals(200,call("PUT","/v1/projects/"+id+"/technical/intake",owner.cookie(),UUID.randomUUID().toString(),"\"0\"",
            "{\"activity\":\"Recarga de veículos elétricos\",\"country\":\"BR\",\"candidateMethodology\":\"VM0038\"}").statusCode());

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
        assertEquals("0,7",saved.at("/readiness/market/citedStockPercent").asText());
        assertEquals("7,5",saved.at("/readiness/market/stockLimitPercent").asText());
        assertEquals("30",saved.at("/readiness/market/salesLimitPercent").asText());
        assertTrue(saved.at("/readiness/market/citationBelowStockLimit").asBoolean());
        assertEquals("CITATION_NOT_SERIES",saved.at("/readiness/market/stockSeries").asText());
        assertEquals("ABSENT",saved.at("/readiness/market/salesSeries").asText());
        assertFalse(saved.at("/readiness/market/closesApplicability").asBoolean());
        assertFalse(saved.at("/readiness/market/usableForE8").asBoolean());
        assertTrue(saved.at("/readiness/market/reason").asText().contains("não fecha o teste"));
        assertTrue(saved.at("/readiness/findings").toString().contains("VMD0049_ROUTE"));
        var opinion=json(call("GET","/v1/projects/"+id+"/technical/open-additionality",
            owner.cookie(),null,null,null));
        assertEquals(404,call("GET","/v1/projects/"+id+"/technical/open-additionality",
            outsider.cookie(),null,null,null).statusCode());
        assertFalse(opinion.path("declarationIsOpinion").asBoolean());
        assertFalse(opinion.path("marketTestClosesApplicability").asBoolean());
        assertFalse(opinion.path("concludesAdditionality").asBoolean());
        assertFalse(opinion.path("usableForE8").asBoolean());
        assertEquals(3,opinion.path("criteria").size());
        assertTrue(opinion.path("criteria").toString().contains("HUMAN_OPINION"));
        assertFalse(opinion.path("criteria").toString().contains("MARKET_ROUTE"));
        assertEquals("EVIDENCE_PENDING",saved.at("/readiness/rights").asText());
        assertTrue(saved.at("/readiness/findings").toString().contains("nome digitado"));
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

        String rights="/v1/projects/"+id+"/technical/open-rights";
        var party=call("PUT",rights+"/parties/HOLDER-1",owner.cookie(),UUID.randomUUID().toString(),"\"0\"",
            "{\"kind\":\"ORGANIZATION\",\"displayName\":\"Titular do carregador\"}");
        assertEquals(200,party.statusCode(),party.body());
        assertFalse(json(party).path("createsLegalPower").asBoolean());
        assertFalse(json(party).path("admitsRights").asBoolean());
        assertEquals(200,call("PUT",rights+"/contracts/CONTRACT-001",owner.cookie(),
            UUID.randomUUID().toString(),"\"0\"",
            "{\"partyCode\":\"HOLDER-1\",\"label\":\"Contrato de direitos\"}").statusCode());
        String contractFile=pdf(owner,id,"CONTRACT","CONTRACT-001");
        String disclosureFile=pdf(owner,id,"DISCLOSURE","DECLARATION");
        String withDisclosure="""
                {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
                 "privateLand":true,"chargerType":"DCFC","propulsion":"BEV",
                 "electricitySource":"SIN","associatedInfrastructure":false,"v2g":false,
                 "rightsPartyCode":"HOLDER-1","rightsContractCode":"CONTRACT-001",
                 "contractFileId":"%s","disclosureFileId":"%s",
                 "driverDisclosureChannel":"ON_CHARGER","driverDisclosureEvidenceRef":"PHOTO-2027-01",
                 "driverDisclosureEffectiveOn":"2027-01-15",
                 "doubleClaimingCheck":"NO_OVERLAP_DECLARED",
                 "effectiveFrom":"2027-02-01"}
                """.formatted(contractFile,disclosureFile);
        var updated=call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),"\"1\"",withDisclosure);
        assertEquals(200,updated.statusCode(),updated.body());
        JsonNode improved=json(updated);
        assertEquals(2,improved.path("version").asInt());
        assertEquals("DECLARED_REVIEW_PENDING",improved.at("/readiness/rights").asText());
        assertFalse(improved.at("/readiness/canUseForE8").asBoolean());
        assertTrue(improved.at("/readiness/findings").toString().contains("ATTACHED_NOT_ADMITTED"));
        assertTrue(improved.at("/readiness/findings").toString().contains("não cria PP jurídico"));
        assertTrue(improved.at("/readiness/findings").toString().contains("aguardando revisão"));
        assertFalse(improved.at("/readiness/findings").toString().contains("PROOF_ADMITTED"));
        assertEquals(0,database.read(c->first(c,
            "SELECT count(*) FROM app_project_authority_request_revision WHERE project_id=?",
            r->r.getInt(1),id)).intValue());
        assertEquals(0,database.read(c->first(c,
            "SELECT count(*) FROM app_project_collaborator_grant WHERE project_id=?",
            r->r.getInt(1),id)).intValue());
        assertFalse(improved.at("/readiness/findings").toString().contains("PHOTO-2027-01"));

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
        // D15: once reconfirmed, only a relevant triage answer makes the declaration stale again.
        assertEquals(200,call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),"\"2\"",withDisclosure).statusCode());
        assertFalse(json(call("GET",path,owner.cookie(),null,null,null)).at("/readiness/stale").asBoolean());
        assertEquals(200,call("PATCH",triage+"/cursor",owner.cookie(),UUID.randomUUID().toString(),"\"1\"",
            "{\"nextStep\":3}").statusCode());
        assertEquals(200,call("PUT",triage+"/steps/1",owner.cookie(),UUID.randomUUID().toString(),"\"2\"",
            "{\"answers\":{\"objective\":\"Planejar rede pública\",\"activityType\":\"MOBILIDADE_ELETRICA\",\"methodologyCandidate\":\"VM0038\"},\"nextStep\":2}"
            ).statusCode());
        JsonNode narrative=json(call("GET",path,owner.cookie(),null,null,null));
        assertFalse(narrative.at("/readiness/stale").asBoolean(),"cursor moves and narrative edits keep the declaration current");
        assertFalse(narrative.at("/readiness/findings").toString().contains("TRIAGE_CHANGED"));
        assertEquals(3,narrative.path("latestTriageVersion").asInt());
        // Q7 of D16: the pre-calculation follows the same D15 rule as the declaration screen.
        String calculation="/v1/projects/"+id+"/technical/open-calculation-readiness";
        assertFalse(staleGate(calculation,owner.cookie()),"cursor moves and narrative edits keep the pre-calculation current");
        // D16 + D15: a project answered before D16 (retired answers stored in step 3, seeded as an older revision
        // because the triage no longer accepts them) keeps them unchanged on a step-3 save of the initial scope only,
        // so the declaration does not go stale.
        database.transaction(c->update(c,"""
            INSERT INTO app_project_triage_revision
              (project_id,organization_id,environment,version,actor_id,current_step,answers)
            SELECT project_id,organization_id,environment,4,actor_id,4,
                   jsonb_set(answers,'{3}','{"vehicleClass":"LDV","propulsion":"BEV","chargerType":"DCFC","design":"INDIVIDUAL",
                     "network":"CLOSED","access":"PRIVATE","electricitySource":"SIN","metering":"SEGREGATED"}'::jsonb)
            FROM app_project_triage_revision WHERE project_id=? AND version=3
            """,id));
        assertEquals(400,call("PUT",triage+"/steps/3",owner.cookie(),UUID.randomUUID().toString(),"\"4\"",
            "{\"answers\":{\"network\":\"OPEN\"},\"nextStep\":4}").statusCode(),"a retired answer is not edited in the triage");
        assertEquals(200,call("PUT",path,owner.cookie(),UUID.randomUUID().toString(),"\"3\"",withDisclosure).statusCode());
        assertFalse(json(call("GET",path,owner.cookie(),null,null,null)).at("/readiness/stale").asBoolean());
        JsonNode carried=json(call("PUT",triage+"/steps/3",owner.cookie(),UUID.randomUUID().toString(),"\"4\"",
            "{\"answers\":{\"vehicleClass\":\"LDV\",\"propulsion\":\"BEV\",\"chargerType\":\"DCFC\",\"design\":\"INDIVIDUAL\"},\"nextStep\":4}"));
        assertEquals("CLOSED",carried.at("/answers/3/network").asText(),"stored triage answer is kept unchanged");
        assertFalse(json(call("GET",path,owner.cookie(),null,null,null)).at("/readiness/stale").asBoolean(),
            "carrying retired answers forward is not a triage change");
        // The declaration owns access and network: it wins over the older triage answer in the diagnosis.
        var outside=carried.at("/guidance/outsidePilot").toString();
        assertTrue(outside.contains("O piloto usa rede fechada, com frota identificada."),outside);
        assertTrue(outside.contains("O piloto usa acesso restrito à frota."),outside);
        assertEquals(200,call("PUT",triage+"/steps/3",owner.cookie(),UUID.randomUUID().toString(),"\"5\"",
            "{\"answers\":{\"propulsion\":\"PHEV\"},\"nextStep\":4}").statusCode());
        JsonNode scope=json(call("GET",path,owner.cookie(),null,null,null));
        assertTrue(scope.at("/readiness/stale").asBoolean(),"a scope answer in step 3 makes it stale");
        assertTrue(scope.at("/readiness/findings").toString().contains("TRIAGE_CHANGED"));
        assertTrue(staleGate(calculation,owner.cookie()),"a scope answer makes the pre-calculation stale too");
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
        assertNull(restricted.market());
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
    private String pdf(Actor actor,String project,String subject,String code)throws Exception {
        String name="CONTRACT".equals(subject)?"contrato.pdf":"divulga.pdf";
        var request=HttpRequest.newBuilder(URI.create(base.toExternalForm())
            .resolve("/v1/projects/"+project+"/technical/open-rights/files/"+subject+"/"+code))
            .header("Cookie",actor.cookie())
            .header("Idempotency-Key",UUID.randomUUID().toString())
            .header("Content-Type","application/pdf")
            .header("X-File-Name",name)
            .PUT(HttpRequest.BodyPublishers.ofString("%PDF-1.4\n1 0 obj\n<< /Type /Catalog >>\nendobj\n%%EOF\n"));
        var response=client.send(request.build(),HttpResponse.BodyHandlers.ofString());
        assertEquals(201,response.statusCode(),response.body());
        JsonNode body=json(response);
        assertEquals("PENDING_REVIEW",body.path("state").asText());
        assertFalse(body.path("usableForE8").asBoolean());
        return body.path("id").asText();
    }
    private long count(String project){
        return database.read(c->first(c,
          "SELECT count(*) FROM app_project_open_applicability_revision WHERE project_id=?",
          r->r.getLong(1),project));
    }
    private boolean staleGate(String path,String cookie)throws Exception {
        var response=call("GET",path,cookie,null,null,null);
        assertEquals(200,response.statusCode(),response.body());
        return json(response).path("gates").valueStream().anyMatch(g->"OPEN_APPLICABILITY".equals(g.path("code").asText())
            &&"STALE".equals(g.path("state").asText()));
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
