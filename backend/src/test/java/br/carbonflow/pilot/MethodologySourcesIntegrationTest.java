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

/** D18 slice 2: sources are attached to a specific registered methodology; nothing here admits or licenses a source. */
@QuarkusTest
@QuarkusTestResource(value = MethodologySourcesIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class MethodologySourcesIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d11");
    private static final UUID OTHER_STAFF = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d12");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a11");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String REG = "/v1/platform/methodology-registry";
    private static final String SRC = REG + "/METHS/1.0/sources";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static final String CONTEXT = "{\"scenario\":\"GENERIC_SCENARIO\",\"title\":\"Contexto genérico\",\"country\":\"BR\"}";

    private static String need(String code, String title) {
        return "{\"code\":\"" + code + "\",\"stage\":\"APPLICABILITY\",\"kind\":\"NORMATIVE\",\"title\":\"" + title
                + "\",\"purpose\":\"Regras do método\",\"sourceId\":\"METHS\",\"sourceEdition\":\"1.0\","
                + "\"officialUri\":\"https://example.org/metodologia\",\"blockedCapability\":\"APPLICABILITY\"}";
    }

    @Test
    void sourcesBelongToOneRegisteredMethodologyAndNeverAdmitAnything() throws Exception {
        String manager = session(MANAGER), other = session(OTHER_STAFF);
        database.transaction(c -> update(c,
                "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        assertEquals(401, call("GET", SRC, null, null, null).statusCode());
        assertEquals(403, call("GET", SRC, manager, null, null).statusCode());
        assertEquals(200, call("POST", "/v1/compliance/session", manager, null, currentCode()).statusCode());
        assertEquals(200, call("POST", "/v1/compliance/session", other, null, currentCode()).statusCode());
        assertEquals(404, call("GET", SRC, other, null, null).statusCode());
        assertEquals(404, call("POST", SRC + "/contexts", other, null, CONTEXT).statusCode());

        // Unregistered methodology: nothing can be attached.
        assertEquals(404, call("GET", SRC, manager, null, null).statusCode());
        assertEquals(404, call("POST", SRC + "/contexts", manager, null, CONTEXT).statusCode());
        assertEquals(201, call("POST", REG, manager, "\"0\"",
                "{\"program\":\"VCS\",\"methodology\":\"METHS\",\"edition\":\"1.0\",\"title\":\"Metodologia S\","
                + "\"rightsHolder\":\"Titular\"}").statusCode());
        assertEquals(0, ok(call("GET", SRC, manager, null, null)).size());

        // Need needs its context first; context then need, idempotent, conflicting content refused.
        assertEquals(404, call("POST", SRC + "/contexts/GENERIC_SCENARIO/needs", manager, null, need("METHS-NORM", "Norma")).statusCode());
        assertEquals(201, call("POST", SRC + "/contexts", manager, null, CONTEXT).statusCode());
        assertEquals(200, call("POST", SRC + "/contexts", manager, null, CONTEXT).statusCode());
        assertEquals(409, call("POST", SRC + "/contexts", manager, null, CONTEXT.replace("Contexto genérico", "Outro")).statusCode());
        var made = call("POST", SRC + "/contexts/GENERIC_SCENARIO/needs", manager, null, need("METHS-NORM", "Norma"));
        assertEquals(201, made.statusCode(), made.body());
        assertEquals(200, call("POST", SRC + "/contexts/GENERIC_SCENARIO/needs", manager, null, need("METHS-NORM", "Norma")).statusCode());
        assertEquals(409, call("POST", SRC + "/contexts/GENERIC_SCENARIO/needs", manager, null, need("METHS-NORM", "Outra")).statusCode());
        // A code is global: another methodology cannot reuse it.
        assertEquals(201, call("POST", REG, manager, "\"0\"",
                "{\"program\":\"VCS\",\"methodology\":\"METHT\",\"edition\":\"1.0\",\"title\":\"Metodologia T\","
                + "\"rightsHolder\":\"Titular\"}").statusCode());
        assertEquals(201, call("POST", REG + "/METHT/1.0/sources/contexts", manager, null, CONTEXT).statusCode());
        assertEquals(409, call("POST", REG + "/METHT/1.0/sources/contexts/GENERIC_SCENARIO/needs", manager, null,
                need("METHS-NORM", "Norma")).statusCode());

        // Counts: one need, no source published, so nothing is "registered"; the methodology stays without a package.
        JsonNode contexts = ok(call("GET", SRC, manager, null, null));
        assertEquals(1, contexts.size());
        assertEquals(1, contexts.get(0).path("needCount").asInt());
        assertEquals(0, contexts.get(0).path("registeredSourceCount").asInt());
        assertTrue(contexts.get(0).path("networkMode").isNull());
        JsonNode entry = null;
        for (var item : ok(call("GET", REG, manager, null, null))) if ("METHS".equals(item.path("methodology").asText())) entry = item;
        assertNotNull(entry);
        assertEquals(1, entry.path("sourceNeeds").asInt());
        assertFalse(entry.path("hasPackage").asBoolean());

        // The readiness console reads the new context with the needs, derived status MISSING_SOURCE_METADATA.
        JsonNode readiness = ok(call("GET", "/v1/platform/source-readiness/METHS?edition=1.0&scenario=GENERIC_SCENARIO", manager, null, null));
        assertEquals("MISSING_SOURCE_METADATA", readiness.path("needs").get(0).path("status").asText());

        // Validation: unknown stage, evidence with a source, exact policy with a policy token, plain http, bad scenario.
        for (String bad : new String[]{need("METHS-X1", "Norma").replace("APPLICABILITY\",\"kind", "NADA\",\"kind"),
                need("METHS-X2", "Norma").replace("NORMATIVE", "PROJECT_EVIDENCE"),
                need("METHS-X3", "Norma").replace("\"sourceEdition\":\"1.0\"", "\"sourceEdition\":\"YEAR\""),
                need("METHS-X4", "Norma").replace("https://", "http://"),
                need("metx5", "Norma")}) {
            assertEquals(422, call("POST", SRC + "/contexts/GENERIC_SCENARIO/needs", manager, null, bad).statusCode(), bad);
        }
        assertEquals(422, call("POST", SRC + "/contexts", manager, null, CONTEXT.replace("GENERIC_SCENARIO", "x")).statusCode());
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private String session(UUID id) {
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'Conta de teste','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES(?,?,statement_timestamp()+interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return token;
    }

    private String currentCode() {
        long counter = database.read(c -> first(c,
                "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint", row -> row.getLong(1)));
        return "{\"code\":\"" + ComplianceStaff.codeAt(OTP, counter) + "\"}";
    }

    private HttpResponse<String> call(String method, String path, String token, String etag, String body) throws Exception {
        return call(method, path, token, etag, body, null);
    }

    private HttpResponse<String> call(String method, String path, String token, String etag, String body, String key)
            throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path));
        if (token != null) builder.header("Cookie", AccountAccess.COOKIE + "=" + token);
        if (etag != null) builder.header("If-Match", etag);
        if (key != null) builder.header("Idempotency-Key", key);
        if (body != null) builder.header("Content-Type", "application/json");
        builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        return client.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String, String> start() {
            var result = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG + ";" + OTHER_STAFF + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.methodology-registry.managers", MANAGER.toString());
            result.put("carbonflow.platform.source-readiness.viewers", MANAGER.toString());
            return result;
        }
    }
}
