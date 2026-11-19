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
@QuarkusTestResource(value = PlatformSourceReadinessIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class PlatformSourceReadinessIntegrationTest {
    private static final UUID STAFF = UUID.fromString("a66f1011-84bf-478a-8b86-2b5fd2881dc4");
    private static final UUID OTHER = UUID.fromString("a66f1011-84bf-478a-8b86-2b5fd2881dc5");
    private static final UUID REVIEWER = UUID.fromString("a66f1011-84bf-478a-8b86-2b5fd2881dc6");
    private static final UUID ORG = UUID.fromString("61e71a49-e926-4eaa-aab8-86b04add8a9f");
    private static final byte[] OTP = "67890123456789012345".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/source-readiness";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void catalogIsDynamicAndLicensingIsNeverPromotedToPlatformAdmission() throws Exception {
        String authorized = actor(STAFF);
        String other = actor(OTHER);
        actor(REVIEWER);
        database.transaction(c -> update(c,
                "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização para verificação') ON CONFLICT(id) DO NOTHING",ORG));

        assertEquals(403, get(API + "/contexts",authorized).statusCode());
        assertEquals(200, post("/v1/compliance/session",authorized, currentCode()).statusCode());
        assertEquals(200, post("/v1/compliance/session",other,currentCode()).statusCode());
        assertEquals(404, get(API + "/contexts",other).statusCode());
        assertEquals(401, get(API + "/contexts",null).statusCode());

        var contexts = body(get(API + "/contexts",authorized));
        assertTrue(contexts.isArray());
        assertTrue(contexts.toString().contains("OPEN_LDV_DCFC_SIN_BR"));
        String base = API + "/VM0038?edition=1.1&scenario=OPEN_LDV_DCFC_SIN_BR";
        var initial = get(base,authorized);
        assertEquals(200,initial.statusCode(),initial.body());
        assertTrue(initial.headers().allValues("Cache-Control").stream()
                .anyMatch(value -> value.contains("no-store")));
        assertEquals("ONLY_SYNTHETIC_RULE_PACKS",body(initial).path("normativeRuleStatus").asText());
        assertEquals("METADATA_ONLY",find(body(initial),"OPEN-METHOD").path("status").asText());
        assertEquals("MISSING_SOURCE_METADATA",find(body(initial),"OPEN-EVR").path("status").asText());

        var filtered = body(get(base + "&stage=BASELINE&status=MISSING_SOURCE_METADATA&limit=1",authorized));
        assertEquals(1, filtered.path("needs").size());
        assertTrue(filtered.path("total").asInt() >= 2);
        assertTrue(filtered.path("nextOffset").isNumber());
        assertEquals(1, body(get(base + "&stage=BASELINE&status=MISSING_SOURCE_METADATA&limit=1&offset=1",authorized))
                .path("needs").size());
        assertEquals(400, get(base + "&limit=101",authorized).statusCode());
        assertEquals(400, get(base + "&stage=INVALID",authorized).statusCode());

        // Registry: no source-code change is required to register an additional methodology.
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_methodology_source_context
                    (methodology,methodology_edition,scenario,title,country,network_mode,
                     vehicle_category,charger_type,energy_system)
                VALUES ('METHX','2.1','TEST_SCOPE_BR','Teste documental isolado','BR','OPEN','LDV','DCFC','SIN')
                """);
            update(c, """
                INSERT INTO app_methodology_source_need
                    (code,methodology,methodology_edition,scenario,stage,kind,title,purpose,mandatory)
                VALUES ('TEST-EVIDENCE','METHX','2.1','TEST_SCOPE_BR','DOCUMENTS',
                        'PROJECT_EVIDENCE','Contrato do projeto','Evidência por proponente',true)
                """);
            return null;
        });
        assertTrue(body(get(API+"/contexts",authorized)).toString().contains("METHX"));
        assertEquals("PROJECT_SPECIFIC_EVIDENCE",find(body(get(API +
                "/METHX?edition=2.1&scenario=TEST_SCOPE_BR",authorized)),
                "TEST-EVIDENCE").path("status").asText());

        // Candidate source metadata is not a license, and a YEAR placeholder is not an edition.
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin)
                VALUES ('BR-LDV-BEV-STOCK-BY-MODEL','2026','EXTERNAL_SOURCE',
                        'Candidato de estoque por modelo','Autor dos dados','EXTERNAL')
                """);
            update(c, """
                INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin)
                VALUES ('VMD0049','1.1','MODULE','Módulo positivo','Verra','EXTERNAL')
                """);
            return null;
        });
        var candidate = body(get(base + "&limit=50",authorized));
        assertEquals("VERSION_SELECTION_REQUIRED",find(candidate,"OPEN-EVR").path("status").asText());
        assertEquals("METADATA_ONLY",find(candidate,"OPEN-POSITIVE").path("status").asText());

        UUID license = UUID.randomUUID();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_content_license
                  (id,source_id,edition,organization_id,environment,purpose,authorization_ref,
                   proof_sha256,submitted_by,starts_at,ends_at)
                VALUES (?, 'VMD0049','1.1',?,'SANDBOX','REQUIREMENT_CATALOG',
                   'Prova interna de testes',?,?,'2025-01-01',statement_timestamp()+interval '1 hour')
                """,license,ORG,"a".repeat(64),STAFF);
            update(c, """
                INSERT INTO app_content_license_decision
                  (license_id,version,outcome,decided_by,reason)
                VALUES (?,1,'APPROVED',?,'Teste da permissão de referência limitada')
                """,license,REVIEWER);
            update(c, """
                INSERT INTO app_content_license_capability(license_id,decision_version,operation)
                VALUES (?,1,'REFERENCE_REQUIREMENT'),(?,1,'RAG_RETRIEVE')
                """,license,license);
            return null;
        });
        JsonNode allowed = find(body(get(base+"&limit=50",authorized)),"OPEN-POSITIVE");
        assertEquals("SCOPED_REFERENCE_NOT_ADMITTED",allowed.path("status").asText());
        assertTrue(allowed.path("referenceAllowedInStaffOrganization").asBoolean());
        assertFalse(allowed.path("ragAllowedInStaffOrganization").asBoolean(),
                "Normative AI remains disabled even if a scoped license includes RAG");
        assertFalse(allowed.path("technicalAdmissionConfirmed").asBoolean());

        database.transaction(c -> update(c, """
            INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason)
            VALUES (?,2,'REVOKED',?,'Revogação na mesma sessão')
            """,license,REVIEWER));
        JsonNode revoked = find(body(get(base+"&limit=50",authorized)),"OPEN-POSITIVE");
        assertEquals("METADATA_ONLY",revoked.path("status").asText());
        assertFalse(revoked.path("referenceAllowedInStaffOrganization").asBoolean());

        assertEquals(404, get(API + "/METHY?edition=1.0&scenario=NOPE",authorized).statusCode());
        assertEquals(404, get(API + "/METHX?edition=2.1&scenario=TEST_SCOPE_BR",other).statusCode());
    }

    private JsonNode find(JsonNode page, String code) {
        for (JsonNode need : page.path("needs")) {
            if (code.equals(need.path("code").asText())) return need;
        }
        fail("Necessidade não encontrada no inventário: " + code);
        return null;
    }

    private String actor(UUID id) {
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'Staff de teste','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """,id,id+"@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
                """,AccessSecrets.digest(token),id);
            return null;
        });
        return token;
    }

    private String currentCode() {
        long counter = database.read(c -> first(c,
            "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint",
            row -> row.getLong(1)));
        return "{\"code\":\"" + ComplianceStaff.codeAt(OTP,counter) + "\"}";
    }

    private HttpResponse<String> get(String path,String token) throws Exception {
        HttpRequest.Builder builder = HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path)).GET();
        if (token != null) builder.header("Cookie",AccountAccess.COOKIE+"="+token);
        return client.send(builder.build(),HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> post(String path,String token,String data) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path))
            .header("Cookie",AccountAccess.COOKIE+"="+token).header("Content-Type","application/json")
            .POST(HttpRequest.BodyPublishers.ofString(data)).build(),HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        assertEquals(200,response.statusCode(),response.body());
        return json.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String,String> start() {
            var result = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff", STAFF+":"+secret+":"+ORG+
                    ";"+OTHER+":"+secret+":"+ORG);
            result.put("carbonflow.compliance.document-key",
                    Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.source-readiness.viewers",STAFF.toString());
            return result;
        }
    }
}
