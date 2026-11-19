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
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = PlatformSourceWorkIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class PlatformSourceWorkIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("260a7df7-07e4-413c-990f-81f97d400124");
    private static final UUID VIEWER = UUID.fromString("260a7df7-07e4-413c-990f-81f97d400125");
    private static final UUID ORG = UUID.fromString("260a7df7-07e4-413c-990f-81f97d400126");
    private static final byte[] OTP = "54321098765432109876".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/source-work";
    private static final String CONTEXT = "?methodology=VM0038&edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void staffCanPlanSourceRequestsWithoutApprovingMethodsOrCustomerEvidence() throws Exception {
        String manager = actor(MANAGER);
        String viewer = actor(VIEWER);
        assertEquals(403,get(API + CONTEXT,manager).statusCode());
        assertEquals(200,post("/v1/compliance/session",manager,currentCode(),null).statusCode());
        assertEquals(200,post("/v1/compliance/session",viewer,currentCode(),null).statusCode());

        JsonNode initial = body(get(API + CONTEXT,viewer));
        assertFalse(initial.path("canManage").asBoolean());
        assertTrue(initial.path("impacts").isArray());
        assertTrue(initial.path("impacts").size() > 0);
        assertEquals(404,put(API+"/OPEN-EVR",viewer,"{\"note\":\"Sem acesso\"}").statusCode());
        assertEquals(404,get(API+"/assignees",viewer).statusCode());
        assertEquals(401,get(API+CONTEXT,null).statusCode());

        JsonNode team = body(get(API + "/assignees",manager));
        assertTrue(team.isArray());
        assertEquals(1,team.size());
        assertEquals(MANAGER.toString(),team.get(0).path("id").asText());
        assertEquals(422,put(API+"/OPEN-RIGHTS",manager,"{\"note\":\"Prova do PP\"}").statusCode());
        assertEquals(404,put(API+"/DOES-NOT-EXIST",manager,"{\"note\":\"Fonte\"}").statusCode());
        assertEquals(422,put(API+"/OPEN-EVR",manager,
                "{\"note\":\"Procurar\",\"ownerId\":\""+VIEWER+"\"}").statusCode());

        String create = "{\"note\":\"Buscar frota por modelo e ano na fonte oficial\",\"ownerId\":\""+MANAGER+"\"}";
        JsonNode first = body(put(API+"/OPEN-EVR",manager,create));
        assertEquals("REQUESTED",first.path("work").path("status").asText());
        assertEquals(1,first.path("work").path("version").asInt());
        assertEquals(1,first.path("history").size());
        assertEquals("Buscar frota por modelo e ano na fonte oficial",first.path("history").get(0).path("note").asText());

        JsonNode repeat = body(put(API+"/OPEN-EVR",manager,
                "{\"note\":\"Tentativa de alteração silenciosa\"}"));
        assertEquals(1,repeat.path("history").size());
        assertEquals(first.path("history").get(0).path("note").asText(),
                     repeat.path("history").get(0).path("note").asText());

        assertEquals(428,post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"SOURCING\",\"note\":\"Investigando\"}",null).statusCode());
        JsonNode changed = body(post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"SOURCING\",\"ownerId\":\""+MANAGER+"\",\"note\":\"Avaliando catálogo Senatran\"}","1"));
        assertEquals(2,changed.path("work").path("version").asInt());
        assertEquals(2,changed.path("history").size());
        assertEquals(409,post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"ON_HOLD\",\"note\":\"Stale\"}","1").statusCode());
        assertEquals(422,post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"REQUESTED\",\"note\":\"Transição regressiva\"}","2").statusCode());

        JsonNode rights = body(post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"WAITING_RIGHTS\",\"note\":\"Aguardando confirmação de direitos\"}","2"));
        assertEquals(3,rights.path("work").path("version").asInt());
        assertEquals(422,post(API+"/OPEN-EVR/revisions",manager,
                "{\"status\":\"READY_FOR_HANDOFF\",\"note\":\"Sem direitos\"}","3").statusCode());
        assertEquals("WAITING_RIGHTS",body(get(API+"/OPEN-EVR",viewer)).path("work").path("status").asText());

        JsonNode board = body(get(API+CONTEXT,manager));
        assertTrue(board.path("canManage").asBoolean());
        assertEquals(1,board.path("work").size());
        assertEquals("WAITING_RIGHTS",board.path("work").get(0).path("status").asText());
        assertEquals("OPEN-EVR",board.path("work").get(0).path("needCode").asText());

        assertEquals(3,body(get(API+"/OPEN-EVR",manager)).path("history").size());
        assertEquals(404,get(API+"/NOT-IN-QUEUE",viewer).statusCode());

        // The work queue is independent from normative reference rights.
        assertEquals("MISSING_SOURCE_METADATA",body(get(
                "/v1/platform/source-readiness/VM0038?edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR&limit=50",manager))
                .path("needs").findValues("code").stream()
                .filter(n -> "OPEN-EVR".equals(n.asText())).findFirst().isPresent()
                ? "MISSING_SOURCE_METADATA" : "NOT_FOUND");
    }

    @Test
    void transitionsAreConstrainedEvenWhenClientControlsAreModified() {
        assertTrue(PlatformSourceWork.allowed("REQUESTED","SOURCING"));
        assertTrue(PlatformSourceWork.allowed("SOURCING","READY_FOR_HANDOFF"));
        assertFalse(PlatformSourceWork.allowed("WAITING_RIGHTS","READY_FOR_HANDOFF"));
        assertFalse(PlatformSourceWork.allowed("REQUESTED","READY_FOR_HANDOFF"));
        assertFalse(PlatformSourceWork.allowed("READY_FOR_HANDOFF","REQUESTED"));
        assertTrue(PlatformSourceWork.allowed("SOURCING","SOURCING"));
    }

    private String actor(UUID id) {
        String token=AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?, 'Staff para testes','ACTIVE',statement_timestamp())
                ON CONFLICT(id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return token;
    }

    private String currentCode() {
        long counter=database.read(c -> first(c,
            "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint",
            row -> row.getLong(1)));
        return "{\"code\":\""+ComplianceStaff.codeAt(OTP,counter)+"\"}";
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path)).GET();
        if(token!=null) builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> put(String path,String token,String data) throws Exception {
        return request(path,token,data,"PUT",null);
    }

    private HttpResponse<String> post(String path,String token,String data,String ifMatch) throws Exception {
        return request(path,token,data,"POST",ifMatch);
    }

    private HttpResponse<String> request(String path,String token,String data,String method,String ifMatch) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path))
            .header("Content-Type","application/json");
        if(token!=null) builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(ifMatch!=null) builder.header("If-Match",ifMatch);
        builder.method(method,HttpRequest.BodyPublishers.ofString(data));
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start() {
            var result=new HashMap<>(super.start());
            String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff",MANAGER+":"+secret+":"+ORG+
                    ";"+VIEWER+":"+secret+":"+ORG);
            result.put("carbonflow.compliance.document-key",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.source-readiness.viewers",MANAGER+","+VIEWER);
            result.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            return result;
        }
    }
}
