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
@QuarkusTestResource(value = PlatformSourceIngestionIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class PlatformSourceIngestionIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("9a81371a-e019-4b54-8a43-c4ff28ad1f30");
    private static final UUID REVIEWER = UUID.fromString("9a81371a-e019-4b54-8a43-c4ff28ad1f31");
    private static final UUID VIEWER = UUID.fromString("9a81371a-e019-4b54-8a43-c4ff28ad1f32");
    private static final UUID ORG = UUID.fromString("9a81371a-e019-4b54-8a43-c4ff28ad1f33");
    private static final byte[] OTP = "91234567890123456789".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/source-ingestion";
    private static final String CONTEXT = "?methodology=VM0038&edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void independentReviewAdmitsOnlyMetadataAndNeverTheNumericalSource() throws Exception {
        String manager=actor(MANAGER);
        String reviewer=actor(REVIEWER);
        String viewer=actor(VIEWER);
        assertEquals(401,get(API+CONTEXT,null).statusCode());
        assertEquals(403,get(API+CONTEXT,manager).statusCode());
        assertEquals(200,post("/v1/compliance/session",manager,currentCode(),null).statusCode());
        assertEquals(200,post("/v1/compliance/session",reviewer,currentCode(),null).statusCode());
        assertEquals(200,post("/v1/compliance/session",viewer,currentCode(),null).statusCode());

        JsonNode managerBoard=body(get(API+CONTEXT,manager),200);
        assertTrue(managerBoard.path("canSubmit").asBoolean());
        assertTrue(managerBoard.path("canReview").asBoolean());
        assertEquals(MANAGER.toString(),managerBoard.path("actorId").asText());
        JsonNode reviewerBoard=body(get(API+CONTEXT,reviewer),200);
        assertFalse(reviewerBoard.path("canSubmit").asBoolean());
        assertTrue(reviewerBoard.path("canReview").asBoolean());
        JsonNode viewerBoard=body(get(API+CONTEXT,viewer),200);
        assertFalse(viewerBoard.path("canSubmit").asBoolean());
        assertFalse(viewerBoard.path("canReview").asBoolean());
        assertEquals(404,post(API,viewer,metadata("2024","Teste frota por modelo 2024"),null).statusCode());
        assertEquals(404,post(API,reviewer,metadata("2024","Teste frota por modelo 2024"),null).statusCode());

        String wrong = metadata("YEAR","Teste frota por modelo 2024");
        assertEquals(422,post(API,manager,wrong,null).statusCode());
        assertEquals(422,post(API,manager,
            metadata("2024","Teste frota por modelo 2024").replace(
                "https://data.example.org/frota/2024","http://data.example.org/frota/2024"),null).statusCode());
        assertEquals(404,post(API,manager,
            metadata("2024","Teste frota por modelo 2024").replace("OPEN-EVR","OPEN-RIGHTS"),null).statusCode());

        String draft = metadata("2024","Teste frota por modelo 2024");
        JsonNode first=body(post(API,manager,draft,null),201);
        String id=first.path("intake").path("id").asText();
        assertEquals("PENDING_REVIEW",first.path("intake").path("state").asText());
        assertEquals("BR-LDV-BEV-STOCK-BY-MODEL",first.path("intake").path("sourceId").asText());
        assertEquals(1,first.path("history").size());
        assertEquals(first.path("intake").path("declaredOriginalSha256").asText(),"a".repeat(64));
        assertFalse(first.toString().contains("bytes"),"The platform intake must remain metadata-only");

        JsonNode repeat=body(post(API,manager,draft,null),201);
        assertEquals(id,repeat.path("intake").path("id").asText());
        assertEquals(1,repeat.path("history").size());
        assertEquals(403,post(API+"/"+id+"/reviews",manager,
            "{\"outcome\":\"ACCEPT_METADATA\",\"reason\":\"Revisão indevida própria\"}","1").statusCode());
        assertEquals(404,post(API+"/"+id+"/reviews",viewer,
            "{\"outcome\":\"REJECT\",\"reason\":\"Não autorizado\"}","1").statusCode());
        assertEquals(428,post(API+"/"+id+"/reviews",reviewer,
            "{\"outcome\":\"ACCEPT_METADATA\",\"reason\":\"Verificado\"}",null).statusCode());
        assertEquals(422,post(API+"/"+id+"/reviews",reviewer,
            "{\"outcome\":\"UNRECOGNIZED\",\"reason\":\"Inválido\"}","1").statusCode());

        JsonNode after=body(post(API+"/"+id+"/reviews",reviewer,
                "{\"outcome\":\"ACCEPT_METADATA\",\"reason\":\"Fonte documental e edição conferidas; não valida dados\"}","1"),200);
        assertEquals("METADATA_ACCEPTED",after.path("intake").path("state").asText());
        assertEquals(2,after.path("intake").path("version").asInt());
        assertEquals(2,after.path("history").size());
        assertEquals(REVIEWER.toString(),after.path("intake").path("reviewedBy").asText());

        assertEquals(409,post(API+"/"+id+"/reviews",reviewer,
            "{\"outcome\":\"REJECT\",\"reason\":\"Replay obsoleto\"}","1").statusCode());
        assertEquals(1,database.read(c -> first(c, """
            SELECT count(*) FROM app_source_version
            WHERE source_id='BR-LDV-BEV-STOCK-BY-MODEL' AND edition='2024' AND origin='EXTERNAL'
            """,row -> row.getInt(1))).intValue());
        assertEquals(0,database.read(c -> first(c, """
            SELECT count(*) FROM app_content_license
            WHERE source_id='BR-LDV-BEV-STOCK-BY-MODEL' AND edition='2024'
            """,row -> row.getInt(1))).intValue());
        assertEquals("VERSION_SELECTION_REQUIRED",
            lookupNeed(body(get("/v1/platform/source-readiness/VM0038"+
                "?edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR&limit=50",manager),200),
                "OPEN-EVR").path("status").asText());

        JsonNode conflict=body(post(API,manager,
                metadata("2024","Outra versão conflitante de titulo"),null),201);
        String other=conflict.path("intake").path("id").asText();
        assertEquals(409,post(API+"/"+other+"/reviews",reviewer,
                "{\"outcome\":\"ACCEPT_METADATA\",\"reason\":\"Tentativa de substituir registro\"}","1").statusCode());
        assertEquals("PENDING_REVIEW",body(get(API+"/"+other,reviewer),200).path("intake").path("state").asText());

        JsonNode correction=body(post(API,manager,metadata("2023","Frota 2023 para correção"),null),201);
        String correctionId=correction.path("intake").path("id").asText();
        assertEquals("NEEDS_CORRECTION",
            body(post(API+"/"+correctionId+"/reviews",reviewer,
                "{\"outcome\":\"REQUEST_CORRECTION\",\"reason\":\"Falta localizador verificável\"}","1"),200)
            .path("intake").path("state").asText());
        assertEquals(0,database.read(c -> first(c, """
            SELECT count(*) FROM app_source_version
            WHERE source_id='BR-LDV-BEV-STOCK-BY-MODEL' AND edition='2023'
            """,row -> row.getInt(1))).intValue());
    }

    private static JsonNode lookupNeed(JsonNode page,String code) {
        for(JsonNode x:page.path("needs"))if(code.equals(x.path("code").asText()))return x;
        fail("Need absent "+code);
        return null;
    }

    private static String metadata(String edition,String title) {
        return "{\"needCode\":\"OPEN-EVR\",\"edition\":\""+edition+
            "\",\"category\":\"EXTERNAL_SOURCE\",\"title\":\""+title+
            "\",\"rightsHolder\":\"Instituicao ficticia de testes\","+
            "\"publisher\":\"Entidade de teste\",\"officialUri\":\"https://data.example.org/frota/2024\","+
            "\"publicationDate\":\"2024-12-31\",\"referenceYear\":2024,"+
            "\"locator\":\"Tabela nacional por ano e modelo\","+
            "\"declaredOriginalSha256\":\""+ "a".repeat(64) +
            "\",\"note\":\"Registro apenas de metadados para teste isolado\"}";
    }

    private String actor(UUID id) {
        String token=AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?, 'Staff de fontes','ACTIVE',statement_timestamp())
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
        long t=database.read(c -> first(c,
            "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint",row -> row.getLong(1)));
        return "{\"code\":\""+ComplianceStaff.codeAt(OTP,t)+"\"}";
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path)).GET();
        if(token!=null)builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path,String token,String payload,String ifMatch) throws Exception {
        var builder=HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path))
            .header("Content-Type","application/json");
        if(token!=null)builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        if(ifMatch!=null)builder.header("If-Match",ifMatch);
        return client.send(builder.POST(HttpRequest.BodyPublishers.ofString(payload)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response,int expected) throws Exception {
        assertEquals(expected,response.statusCode(),response.body());
        return json.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start() {
            var result=new HashMap<>(super.start());
            String secret=Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff",MANAGER+":"+secret+":"+ORG+
                    ";"+REVIEWER+":"+secret+":"+ORG+";"+VIEWER+":"+secret+":"+ORG);
            result.put("carbonflow.compliance.document-key",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.source-readiness.viewers",MANAGER+","+REVIEWER+","+VIEWER);
            result.put("carbonflow.platform.source-work.managers",MANAGER.toString());
            result.put("carbonflow.platform.source-ingestion.reviewers",MANAGER+","+REVIEWER);
            return result;
        }
    }
}
