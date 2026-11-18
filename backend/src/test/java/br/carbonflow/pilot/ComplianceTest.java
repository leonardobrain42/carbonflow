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
@QuarkusTestResource(value = ComplianceTest.Settings.class, restrictToAnnotatedClass = true)
class ComplianceTest {
    private static final UUID STAFF = UUID.fromString("ec28782d-712e-4aa8-b3c0-85f90fdd2826");
    private static final UUID OWNER_STAFF = UUID.fromString("7f067263-a45f-4e2a-93ce-26f94bb38258");
    private static final UUID ORG = UUID.fromString("a3c583fe-6279-4ba9-a40f-60bcbe6e4355");
    private static final byte[] OTP = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/compliance";
    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void mfaAndEvidenceAreBoundToTheStaffAccountSessionOrganizationAndRequest() throws Exception {
        assertEquals("287082", ComplianceStaff.codeAt(OTP, 1));
        String reviewer = actor(STAFF);
        String secondSession = actor(STAFF);
        String owner = actor(OWNER_STAFF);
        String otherOwner = actor(UUID.randomUUID());
        String project = project(owner, OWNER_STAFF, ORG);
        UUID otherId = lookup(otherOwner);
        String unrelated = project(otherOwner, otherId, UUID.randomUUID());
        String requestId = submit(owner, project);
        submit(otherOwner, unrelated);
        String path = "/v1/projects/" + project + "/authority-requests/" + requestId;
        String file = UUID.randomUUID().toString();
        String pdf = "%PDF-1.7\nconteudo sintetico\n%%EOF";
        assertEquals(415, call("PUT", path + "/documents/" + file, owner, "text", "application/octet-stream").statusCode());
        assertEquals(200, call("PUT", path + "/documents/" + file, owner, pdf, "application/octet-stream").statusCode());
        assertNotEquals(pdf, database.read(c -> first(c,
                "SELECT encode(encrypted,'escape') FROM app_compliance_evidence WHERE id = ?",
                row -> row.getString(1), UUID.fromString(file))));
        assertEquals(404, call("GET", path + "/documents/" + file, otherOwner, null, null).statusCode());

        assertEquals(403, call("GET", API + "/queue", reviewer, null, null).statusCode());
        assertEquals(404, call("GET", API + "/session", otherOwner, null, null).statusCode());
        String code = currentCode();
        assertEquals(200, call("POST", API + "/session", reviewer, code, "application/json").statusCode());
        assertEquals(403, call("POST", API + "/session", secondSession, code, "application/json").statusCode());
        assertEquals(403, call("GET", API + "/queue", secondSession, null, null).statusCode());
        var list = call("GET", API + "/queue", reviewer, null, null);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(java.util.List.of(project), body(list).path("cases").findValuesAsText("projectId"));

        assertEquals(200, call("POST", API + "/session", owner, currentCode(), "application/json").statusCode());
        assertEquals(0, body(call("GET", API + "/queue", owner, null, null)).path("cases").size());
        String download = API + "/projects/" + project + "/requests/" + requestId + "/documents/" + file;
        assertEquals(404, call("GET", download, owner, null, null).statusCode());
        assertEquals(404, call("GET", API + "/projects/" + unrelated + "/requests/"
                + requestId + "/documents/" + file, reviewer, null, null).statusCode());
        var result = call("GET", download, reviewer, null, null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(pdf, result.body());
        assertTrue(result.headers().allValues("Cache-Control").stream().anyMatch(value -> value.contains("no-store")));
        assertTrue(result.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertEquals(200, call("POST", path + "/withdraw", owner, null, null, "\"1\"").statusCode());
        assertEquals(404, call("GET", download, reviewer, null, null).statusCode());
        assertEquals(404, call("GET", path + "/documents/" + file, owner, null, null).statusCode());
    }

    private String currentCode() {
        long epoch = database.read(c -> first(c, "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint",
                row -> row.getLong(1)));
        return "{\"code\":\"" + ComplianceStaff.codeAt(OTP, epoch) + "\"}";
    }

    private String submit(String owner, String project) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = call("PUT", "/v1/projects/" + project + "/authority-requests/" + id, owner,
                """
                {"power":"SEND_INVITATIONS","representationBasis":"Ata sintética","evidenceReference":"DOC-SIM"}
                """, "application/json");
        assertEquals(200, response.statusCode(), response.body());
        return id;
    }

    private String project(String cookie, UUID identity, UUID org) throws Exception {
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES(?, 'SANDBOX','Org sintética')", org);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')", org, identity);
            return null;
        });
        var response = call("POST", "/v1/projects", cookie,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto sintético\"}", "application/json");
        assertEquals(201, response.statusCode(), response.body());
        return body(response).path("projectId").asText();
    }

    private String actor(UUID id) {
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

    private UUID lookup(String token) {
        return database.read(c -> first(c, "SELECT identity_id FROM app_account_session WHERE token_hash = ?",
                row -> row.getObject(1, UUID.class), AccessSecrets.digest(token)));
    }

    private HttpResponse<String> call(String method, String path, String token, String data, String media) throws Exception {
        return call(method, path, token, data, media, null);
    }

    private HttpResponse<String> call(String method, String path, String token, String data, String media, String etag) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path))
                .header("Cookie", AccountAccess.COOKIE + "=" + token)
                .header("Idempotency-Key", UUID.randomUUID().toString());
        if (media != null) request.header("Content-Type", media);
        if (etag != null) request.header("If-Match", etag);
        return client.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override
        public Map<String, String> start() {
            var result = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff", STAFF + ":" + secret + ":" + ORG + ";"
                    + OWNER_STAFF + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            return result;
        }
    }
}
