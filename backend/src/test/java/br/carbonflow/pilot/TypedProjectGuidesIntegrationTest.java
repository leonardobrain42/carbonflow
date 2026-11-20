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

@QuarkusTest
@QuarkusTestResource(value=AccountAccessTest.Inbox.class,restrictToAnnotatedClass=true)
class TypedProjectGuidesIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client=HttpClient.newHttpClient();
    private static String key(){return UUID.randomUUID().toString();}
    private record Actor(UUID id,String cookie){}
    private static final String INTAKE = """
        {"activity":"Carregamento público de veículos elétricos","country":"BR",
         "state":"DF","locality":"Brasília","candidateMethodology":"VM0038",
         "referenceActDate":"2027-09-27","notes":"Rede aberta em estudo"}
        """;

    @Test
    void typedVm0038DefinitionsPreserveLegacyAndRejectInvalidTypedAnswers() throws Exception {
        Actor owner=actor(),other=actor();
        String project=project(owner,organization(owner.id()));
        String root="/v1/projects/"+project+"/technical";
        assertEquals(200,call("PUT",root+"/intake",owner.cookie(),key(),"\"0\"",null,INTAKE).statusCode());
        String url=root+"/typed-guides";
        assertEquals(401,call("GET",url,null,null,null,null,null).statusCode());
        assertEquals(404,call("GET",url,other.cookie(),null,null,null,null).statusCode());
        assertEquals(404,call("GET","/v1/projects/PIL-SYN-BR-001/technical/typed-guides",
            owner.cookie(),null,null,null,null).statusCode());
        var catalog=json(call("GET",url,owner.cookie(),null,null,null,null));
        assertEquals("VM0038_V1_1",catalog.path("methodology").asText());
        assertEquals(6,catalog.path("guides").size());
        JsonNode applicability=guide(catalog,"applicability");
        assertEquals(1,applicability.path("definitionVersion").asInt());
        assertEquals("NOT_STARTED",applicability.path("state").asText());
        assertTrue(applicability.at("/fields/0/options").isArray());
        assertEquals("BOOLEAN",findField(applicability,"unrestrictedAccess").path("type").asText());

        // Store a deliberately ambiguous original guide; typed schema only reports it as legacy.
        assertEquals(200,call("PUT",root+"/guides/applicability",owner.cookie(),null,"\"0\"",null,
            "{\"boundary\":\"estacoes a identificar\",\"incidence\":\"A confirmar\"}").statusCode());
        var legacy=json(call("GET",url,owner.cookie(),null,null,null,null));
        assertTrue(guide(legacy,"applicability").path("legacyNeedsReview").toString().contains("boundary"));
        assertEquals("LEGACY_UNSTRUCTURED_REVIEW_REQUIRED",
            guide(legacy,"applicability").path("state").asText());

        String target=url+"/applicability";
        assertEquals(428,call("PUT",target,owner.cookie(),key(),null,"1","{}").statusCode());
        assertEquals(409,call("PUT",target,owner.cookie(),key(),"\"0\"","999","{}").statusCode());
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"0\"","1",
            "{\"unrestrictedAccess\":\"true\"}").statusCode());
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"0\"","1",
            "{\"incidence\":\"Não aplicável\"}").statusCode());
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"0\"","1",
            "{\"incidence\":\"Aplicável\",\"justification\":\"oculto\"}").statusCode());
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"0\"","1",
            "{\"stationCode\":\"ST-FAKE\"}").statusCode());

        // Canonical site/charger are selected as references, never recreated in guide JSON.
        String site=root+"/open-sites/sites/ST-01";
        assertEquals(200,call("PUT",site,owner.cookie(),key(),"\"0\"",null,"""
            {"name":"Estação Norte","municipality":"Brasília","state":"DF",
             "operationalState":"PLANNED","plannedOn":"2027-01-01",
             "commissionedOn":null,"sourceReference":"DOC-ESTACAO-1"}
            """).statusCode());
        assertEquals(200,call("PUT",root+"/open-monitoring/chargers/CH-01",owner.cookie(),key(),
            "\"0\"",null,"""
            {"siteCode":"ST-01","chargerKind":"DCFC","ratedPowerKw":150,
             "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
             "sourceReference":"FICHA-CHARGER-1"}
            """).statusCode());
        var references=json(call("GET",url,owner.cookie(),null,null,null,null));
        assertTrue(findField(guide(references,"applicability"),"stationCode")
            .path("options").toString().contains("ST-01"));
        assertEquals("ST-01",findField(guide(references,"applicability"),"chargerCode")
            .path("optionParents").path("CH-01").asText());
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"0\"","1",
            "{\"stationCode\":\"ST-01\",\"chargerCode\":\"CH-OTHER\"}").statusCode());

        String payload="{\"incidence\":\"Não aplicável\",\"justification\":\"Verificar norma\","+
            "\"stationCode\":\"ST-01\",\"chargerCode\":\"CH-01\","+
            "\"unrestrictedAccess\":true,\"plannedDate\":\"2027-09-27\"}";
        String receipt=key();
        var accepted=call("PUT",target,owner.cookie(),receipt,"\"0\"","1",payload);
        assertEquals(200,accepted.statusCode(),accepted.body());
        assertEquals(1,json(accepted).path("version").asInt());
        assertEquals("Não aplicável",json(accepted).at("/answers/incidence").asText());
        assertTrue(json(accepted).at("/answers/unrestrictedAccess").isBoolean());
        assertEquals("FORM_COMPLETED_DECLARED",json(accepted).path("state").asText());
        assertFalse(json(accepted).path("normativeApproved").asBoolean());
        assertEquals(json(accepted),json(call("PUT",target,owner.cookie(),receipt,"\"0\"","1",payload)));
        assertEquals(409,call("PUT",target,owner.cookie(),receipt,"\"0\"","1",
            "{\"incidence\":\"Aplicável\"}").statusCode());
        assertEquals(412,call("PUT",target,owner.cookie(),key(),"\"0\"","1",payload).statusCode());
        var original=json(call("GET",root+"/guides/applicability/revisions/1",
            owner.cookie(),null,null,null,null));
        assertEquals("estacoes a identificar",original.at("/answers/boundary").asText());
        var prior=json(call("GET",target+"/revisions/1",owner.cookie(),null,null,null,null));
        assertEquals("ST-01",prior.at("/answers/stationCode").asText());
        assertEquals(1,prior.at("/referenceVersions/stationCode").asInt());
        assertEquals(1,prior.at("/referenceVersions/chargerCode").asInt());
        assertEquals(1,prior.path("definitionVersion").asInt());
        assertEquals(1L,count("app_typed_guide_revision",project));
        assertEquals(1L,count("app_technical_guide_revision",project));
        assertEquals(0L,count("pilot_import_file",project));
        // Revision is pinned; a later non-DCFC revision cannot silently remain selectable.
        assertEquals(200,call("PUT",root+"/open-monitoring/chargers/CH-01",
            owner.cookie(),key(),"\"1\"",null,"""
            {"siteCode":"ST-01","chargerKind":"AC","ratedPowerKw":150,
             "grid":"SIN","topology":"GRID_ONLY","timeZone":"America/Sao_Paulo",
             "sourceReference":"FICHA-CHARGER-2"}
            """).statusCode());
        var stale=json(call("GET",url,owner.cookie(),null,null,null,null));
        assertEquals("STALE_REFERENCE_REVIEW_REQUIRED",
            guide(stale,"applicability").path("state").asText());
        assertFalse(findField(guide(stale,"applicability"),"chargerCode")
            .path("options").toString().contains("CH-01"));
        assertEquals(400,call("PUT",target,owner.cookie(),key(),"\"1\"","1",
            payload).statusCode());
    }

    @Test
    void numericListsDatesAndConditionsAreValidatedWithoutConvertingStrings() throws Exception {
        Actor owner=actor();
        String project=project(owner,organization(owner.id()));
        String root="/v1/projects/"+project+"/technical";
        assertEquals(200,call("PUT",root+"/intake",owner.cookie(),key(),"\"0\"",null,INTAKE).statusCode());
        String quant=root+"/typed-guides/quantification";
        for(String invalid:new String[]{
            "{\"vintage\":\"2027\"}","{\"vintage\":2101}",
            "{\"periodStart\":\"2027-02-30\"}",
            "{\"periodStart\":\"2028-01-01\",\"periodEnd\":\"2027-01-01\"}",
            "{\"sourceKinds\":[\"ED\",\"ED\"]}",
            "{\"sourceKinds\":[\"UNKNOWN\"]}"
        })assertEquals(400,call("PUT",quant,owner.cookie(),key(),"\"0\"","1",invalid).statusCode(),invalid);
        String valid="{\"vintage\":2027,\"periodStart\":\"2027-01-01\","+
            "\"periodEnd\":\"2027-12-31\",\"sourceKinds\":[\"ED\",\"EC\"]}";
        var saved=call("PUT",quant,owner.cookie(),key(),"\"0\"","1",valid);
        assertEquals(200,saved.statusCode(),saved.body());
        assertTrue(json(saved).at("/answers/vintage").isIntegralNumber());
        assertEquals(2,json(saved).at("/answers/sourceKinds").size());
        String baseline=root+"/typed-guides/baseline";
        assertEquals(400,call("PUT",baseline,owner.cookie(),key(),"\"0\"","1",
            "{\"efficiencyValue\":0}").statusCode());
        assertEquals(400,call("PUT",baseline,owner.cookie(),key(),"\"0\"","1",
            "{\"efficiencyValue\":17.5544}").statusCode());
        assertEquals(400,call("PUT",baseline,owner.cookie(),key(),"\"0\"","1",
            "{\"efficiencyValue\":17.55}").statusCode());
        var rate=call("PUT",baseline,owner.cookie(),key(),"\"0\"","1",
            "{\"efficiencyValue\":17.55,\"efficiencyUnit\":\"kWh/100km\",\"vehicleClass\":\"LDV\",\"referenceYear\":2025}");
        assertEquals(200,rate.statusCode(),rate.body());
        assertEquals("kWh/100km",json(rate).at("/answers/efficiencyUnit").asText());
    }

    private JsonNode guide(JsonNode catalog,String id){
        for(JsonNode guide:catalog.path("guides"))if(id.equals(guide.path("id").asText()))return guide;
        throw new AssertionError("Guide missing "+id);
    }
    private JsonNode findField(JsonNode guide,String key){
        for(JsonNode field:guide.path("fields"))if(key.equals(field.path("key").asText()))return field;
        throw new AssertionError("Field missing "+key);
    }
    private long count(String table,String project){
        return database.read(c->first(c,"SELECT count(*) FROM "+table+" WHERE project_id=?",
            r->r.getLong(1),project));
    }
    private Actor actor(){
        UUID id=UUID.randomUUID();String token=AccessSecrets.newToken();
        database.transaction(c->{
            update(c,"""
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?, 'Typed guia','ACTIVE',statement_timestamp())
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
            update(c,"INSERT INTO app_organization(id,environment,display_name) VALUES(?,'SANDBOX','Schema teste')",id);
            update(c,"INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')",id,owner);
            return null;
        });return id;
    }
    private String project(Actor owner,UUID org) throws Exception{
        var created=call("POST","/v1/projects",owner.cookie(),key(),null,null,
            "{\"organizationId\":\""+org+"\",\"title\":\"Projeto tipado\"}");
        assertEquals(201,created.statusCode(),created.body());
        return json(created).path("projectId").asText();
    }
    private HttpResponse<String> call(String method,String path,String cookie,String key,
        String match,String schema,String data) throws Exception{
        var builder=HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if(cookie!=null)builder.header("Cookie",cookie);
        if(key!=null)builder.header("Idempotency-Key",key);
        if(match!=null)builder.header("If-Match",match);
        if(schema!=null)builder.header("X-Guide-Definition-Version",schema);
        if(data!=null)builder.header("Content-Type","application/json");
        return client.send(builder.method(method,data==null?
            HttpRequest.BodyPublishers.noBody():HttpRequest.BodyPublishers.ofString(data))
            .build(),HttpResponse.BodyHandlers.ofString());
    }
    private JsonNode json(HttpResponse<String> response)throws Exception{return mapper.readTree(response.body());}
}
