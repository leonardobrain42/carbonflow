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

/** CF-DATA-001: licences are registered, decided by someone else and revoked; nothing here enables a gate. */
@QuarkusTest
@QuarkusTestResource(value = ContentLicensesIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class ContentLicensesIntegrationTest {
    private static final UUID SUBMITTER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d31");
    private static final UUID APPROVER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d32");
    private static final UUID DUAL = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d33");
    private static final UUID OTHER_STAFF = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d34");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a31");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/source-licenses";
    private static final String SOURCE = "LICTEST-SRC";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String licence(String hash, String purpose, String starts, String ends) {
        return "{\"sourceId\":\"" + SOURCE + "\",\"edition\":\"1\",\"purpose\":\"" + purpose
                + "\",\"authorizationRef\":\"Contrato interno de teste 2026\",\"proofSha256\":\"" + hash
                + "\",\"startsAt\":\"" + starts + "\"" + (ends == null ? "" : ",\"endsAt\":\"" + ends + "\"") + "}";
    }

    @Test
    void aLicenceIsSubmittedDecidedByAnotherPersonAndRevokedWithoutEnablingAnything() throws Exception {
        // The deep link of the administration screen is served as the SPA (CI serves routes from this list).
        var page = call("GET", "/workspace/platform/licenses", null, null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        String hash = "e".repeat(64);
        String ok = licence(hash, "REQUIREMENT_CATALOG", "2026-01-01T00:00:00Z", "2099-01-01T00:00:00Z");
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG);
            update(c, "INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin) "
                    + "VALUES (?,'1','EXTERNAL_SOURCE','Fonte de teste de licenças','Titular de teste','EXTERNAL') ON CONFLICT DO NOTHING", SOURCE);
            return null;
        });
        String submitter = session(SUBMITTER), approver = session(APPROVER), dual = session(DUAL), other = session(OTHER_STAFF);
        assertEquals(401, call("GET", API, null, null, null).statusCode());
        assertEquals(403, call("GET", API, submitter, null, null).statusCode());
        for (String who : new String[]{submitter, approver, dual, other})
            assertEquals(200, call("POST", "/v1/compliance/session", who, null, currentCode()).statusCode());

        // Staff outside both rosters see nothing and change nothing.
        assertEquals(404, call("GET", API, other, null, null).statusCode());
        assertEquals(404, call("POST", API, other, null, ok).statusCode());
        // The approver is not a submitter, and the submitter is not an approver.
        assertEquals(404, call("POST", API, approver, null, ok).statusCode());

        // Invalid submissions.
        assertEquals(422, call("POST", API, submitter, null, licence("abc", "REQUIREMENT_CATALOG", "2026-01-01T00:00:00Z", null)).statusCode());
        assertEquals(422, call("POST", API, submitter, null, licence(hash, "OUTRA", "2026-01-01T00:00:00Z", null)).statusCode());
        assertEquals(422, call("POST", API, submitter, null, licence(hash, "REQUIREMENT_CATALOG", "01/01/2026", null)).statusCode());
        assertEquals(422, call("POST", API, submitter, null, licence(hash, "REQUIREMENT_CATALOG", "2026-01-01T00:00:00Z", "2025-01-01T00:00:00Z")).statusCode());
        assertEquals(422, call("POST", API, submitter, null, ok.replace(SOURCE, "FONTE-QUE-NAO-EXISTE")).statusCode());

        // Submission: pending, no capability, version 0.
        var created = call("POST", API, submitter, null, ok);
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("\"0\"", created.headers().firstValue("ETag").orElse(""));
        JsonNode item = json.readTree(created.body());
        String id = item.path("id").asText();
        assertEquals("PENDING", item.path("state").asText());
        assertEquals(0, item.path("operations").size());
        assertEquals(SOURCE, item.path("sourceId").asText());
        assertTrue(item.path("limits").asText().contains("não habilita cálculo"));
        assertEquals(1, ok(call("GET", API, approver, null, null)).findValues("id").stream()
                .filter(v -> v.asText().equals(id)).count());

        String decision = API + "/" + id + "/decision";
        String approve = "{\"outcome\":\"APPROVED\",\"reason\":\"Contrato conferido pelo responsável.\",\"operations\":[\"REFERENCE_REQUIREMENT\",\"EXTRACT_TEXT\"]}";
        // Only approvers decide; the submitter is not one.
        assertEquals(404, call("POST", decision, submitter, "\"0\"", approve).statusCode());
        // Concurrency contract.
        assertEquals(428, call("POST", decision, approver, null, approve).statusCode());
        assertEquals(412, call("POST", decision, approver, "\"5\"", approve).statusCode());
        // Invalid decisions.
        assertEquals(422, call("POST", decision, approver, "\"0\"", approve.replace("[\"REFERENCE_REQUIREMENT\",\"EXTRACT_TEXT\"]", "[]")).statusCode());
        assertEquals(422, call("POST", decision, approver, "\"0\"", approve.replace("EXTRACT_TEXT", "FAZER_TUDO")).statusCode());
        assertEquals(422, call("POST", decision, approver, "\"0\"", approve.replace("Contrato conferido pelo responsável.", "curto")).statusCode());
        assertEquals(422, call("POST", decision, approver, "\"0\"", approve.replace("APPROVED", "DENIED")).statusCode());
        assertEquals(422, call("POST", decision, approver, "\"0\"", approve.replace("APPROVED", "REVOKED")).statusCode());
        assertEquals(404, call("POST", API + "/" + UUID.randomUUID() + "/decision", approver, "\"0\"", approve).statusCode());

        // Dual custody: someone who holds both privileges still cannot approve their own submission.
        var own = json.readTree(call("POST", API, dual, null, ok).body());
        assertEquals(403, call("POST", API + "/" + own.path("id").asText() + "/decision", dual, "\"0\"", approve).statusCode());

        // Independent approval.
        var approved = call("POST", decision, approver, "\"0\"", approve);
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals("\"1\"", approved.headers().firstValue("ETag").orElse(""));
        JsonNode view = json.readTree(approved.body());
        assertEquals("APPROVED", view.path("state").asText());
        assertEquals(APPROVER.toString(), view.path("decidedBy").asText());
        assertEquals("EXTRACT_TEXT", view.path("operations").get(0).asText());
        assertEquals("REFERENCE_REQUIREMENT", view.path("operations").get(1).asText());
        // The decision is final until revoked.
        assertEquals(409, call("POST", decision, approver, "\"1\"", approve).statusCode());

        // Revocation: only an approved licence, with the current version, and it removes every operation at once.
        String revoke = API + "/" + id + "/revoke";
        String why = "{\"reason\":\"Autorização encerrada pelo titular.\"}";
        assertEquals(404, call("POST", revoke, submitter, "\"1\"", why).statusCode());
        assertEquals(428, call("POST", revoke, approver, null, why).statusCode());
        assertEquals(412, call("POST", revoke, approver, "\"0\"", why).statusCode());
        assertEquals(422, call("POST", revoke, approver, "\"1\"", "{\"reason\":\"x\"}").statusCode());
        var revoked = call("POST", revoke, approver, "\"1\"", why);
        assertEquals(200, revoked.statusCode(), revoked.body());
        JsonNode closed = json.readTree(revoked.body());
        assertEquals("REVOKED", closed.path("state").asText());
        assertEquals(2, closed.path("decisionVersion").asInt());
        assertEquals(0, closed.path("operations").size());
        assertEquals(409, call("POST", revoke, approver, "\"2\"", why).statusCode());
        // History is kept: the approved version and its operations are still stored.
        Long kept = database.read(c -> first(c, "SELECT count(*) FROM app_content_license_capability WHERE license_id=?::uuid AND decision_version=1",
                r -> r.getLong(1), id));
        assertEquals(2L, kept.longValue());

        // A denial is also final and cannot be revoked.
        var denied = json.readTree(call("POST", API, submitter, null, ok).body());
        String deniedId = denied.path("id").asText();
        var rejection = call("POST", API + "/" + deniedId + "/decision", approver, "\"0\"",
                "{\"outcome\":\"DENIED\",\"reason\":\"Comprovante não cobre esta finalidade.\",\"operations\":[]}");
        assertEquals(200, rejection.statusCode(), rejection.body());
        assertEquals("DENIED", json.readTree(rejection.body()).path("state").asText());
        assertEquals(409, call("POST", API + "/" + deniedId + "/revoke", approver, "\"1\"", why).statusCode());

        // Every operation leaves an audit event, and nothing else was enabled.
        Long audited = database.read(c -> first(c, "SELECT count(*) FROM app_compliance_access_event WHERE action IN "
                + "('SUBMIT_CONTENT_LICENSE','DECIDE_CONTENT_LICENSE','VIEW_CONTENT_LICENSES') AND actor_id IN (?,?,?)",
                r -> r.getLong(1), SUBMITTER, APPROVER, DUAL));
        // 3 submissions + approval + revocation + denial + 1 listing; refused attempts roll back and leave no event.
        assertEquals(7L, audited.longValue());
        // Cleanup: the fixture source and licences do not leak into other tests.
        database.transaction(c -> {
            update(c, "DELETE FROM app_content_license_capability WHERE license_id IN (SELECT id FROM app_content_license WHERE source_id=?)", SOURCE);
            update(c, "DELETE FROM app_content_license_decision WHERE license_id IN (SELECT id FROM app_content_license WHERE source_id=?)", SOURCE);
            update(c, "DELETE FROM app_content_license WHERE source_id=?", SOURCE);
            update(c, "DELETE FROM app_source_version WHERE source_id=?", SOURCE);
            return null;
        });
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
            result.put("carbonflow.compliance.staff", SUBMITTER + ":" + secret + ":" + ORG + ";" + APPROVER + ":" + secret + ":" + ORG
                    + ";" + DUAL + ":" + secret + ":" + ORG + ";" + OTHER_STAFF + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.content-licenses.submitters", SUBMITTER + "," + DUAL);
            result.put("carbonflow.platform.content-licenses.approvers", APPROVER + "," + DUAL);
            return result;
        }
    }
}
