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
    private static final UUID OWNER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d35");
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
        // D19 extended: a PUBLIC licence is declared internally by the owner; this is not an independent approval.
        String owner = session(OWNER);
        assertEquals(200, call("POST", "/v1/compliance/session", owner, null, currentCode()).statusCode());
        String pub = API + "/public";
        String legal = "c".repeat(64), proof = "d".repeat(64);
        String grants = "[{\"operation\":\"STORE_ORIGINAL\",\"groundKind\":\"LICENSE_GRANT\",\"grounds\":\"Cláusula 3: reproduzir a obra.\"},"
                + "{\"operation\":\"EXTRACT_TEXT\",\"groundKind\":\"LEGAL_INTERPRETATION\",\"grounds\":\"Valores numéricos: Lei 9.610/1998, art. 7º, §2º.\"}]";
        String declare = "{\"sourceId\":\"" + SOURCE + "\",\"edition\":\"1\",\"purpose\":\"AUTHORIZED_PROCESSING\",\"licenseIdentifier\":\"CC-BY-ND-3.0\","
                + "\"legalTextSha256\":\"" + legal + "\",\"evidenceUri\":\"https://example.invalid/pagina\",\"evidenceCapturedAt\":\"2026-11-25T20:44:00Z\","
                + "\"evidenceSha256\":\"" + proof + "\",\"startsAt\":\"2026-11-25T20:44:00Z\",\"startsAtBasis\":\"CAPTURE_DATE\","
                + "\"reason\":\"Rodapé do site declara a licença pública.\",\"operations\":" + grants + "}";
        assertEquals(404, call("POST", pub, submitter, null, declare).statusCode()); // not an owner
        assertEquals(404, call("POST", pub, approver, null, declare).statusCode());
        assertEquals(422, call("POST", pub, owner, null, declare.replace("CC-BY-ND-3.0", "CC-BY-4.0")).statusCode());
        assertEquals(422, call("POST", pub, owner, null, declare.replace("CAPTURE_DATE", "DOCUMENT")).statusCode());
        // The start cannot precede the evidence unless availability is proven.
        assertEquals(422, call("POST", pub, owner, null, declare.replace("\"startsAt\":\"2026-11-25T20:44:00Z\"", "\"startsAt\":\"2026-01-01T00:00:00Z\"")).statusCode());
        assertEquals(422, call("POST", pub, owner, null, declare.replace("\"startsAtBasis\":\"CAPTURE_DATE\"", "\"startsAtBasis\":\"PROVEN_AVAILABILITY\"").replace("\"startsAt\":\"2026-11-25T20:44:00Z\"", "\"startsAt\":\"2026-12-01T00:00:00Z\"")).statusCode());
        assertEquals(422, call("POST", pub, owner, null, declare.replace("https://example.invalid/pagina", "http://x")).statusCode());
        // ND: redistributing derivatives is outside the licence, and an argued operation cannot pose as a clause.
        assertEquals("OPERACAO_FORA_DA_LICENCA", json.readTree(call("POST", pub, owner, null, declare.replace("EXTRACT_TEXT", "EXPORT_DERIVATIVES")).body()).path("code").asText());
        assertEquals("FUNDAMENTO_NAO_E_CLAUSULA", json.readTree(call("POST", pub, owner, null, declare.replace("LEGAL_INTERPRETATION", "LICENSE_GRANT")).body()).path("code").asText());
        assertEquals(422, call("POST", pub, owner, null, declare.replace("Cláusula 3: reproduzir a obra.", "curto")).statusCode());
        var declared = call("POST", pub, owner, null, declare);
        assertEquals(201, declared.statusCode(), declared.body());
        JsonNode pl = json.readTree(declared.body());
        assertEquals("DECLARED_INTERNAL", pl.path("state").asText());
        assertEquals("OWNER_INTERNAL", pl.path("decisionBasis").asText());
        assertEquals("PUBLIC_LICENSE", pl.path("kind").asText());
        assertEquals("CAPTURE_DATE", pl.path("startsAtBasis").asText());
        assertEquals(2, pl.path("operations").size());
        // No consumer sees it as approved: the policy needs APPROVED by a different person and an organization.
        Long inert = database.read(c -> first(c, "SELECT count(*) FROM app_content_license l JOIN app_content_license_decision d ON d.license_id=l.id "
                + "WHERE l.id=?::uuid AND d.outcome='APPROVED'", r -> r.getLong(1), pl.path("id").asText()));
        assertEquals(0L, inert.longValue());
        // The grounds are stored per operation and are immutable.
        Long grounds = database.read(c -> first(c, "SELECT count(*) FROM app_content_license_declared_ground WHERE license_id=?::uuid",
                r -> r.getLong(1), pl.path("id").asText()));
        assertEquals(2L, grounds.longValue());
        // BL-DATA-01: a public-domain declaration by the open-data portal covers the data operations only; the AI operations
        // are not part of it and nothing here becomes an approval.
        String pd = declare.replace("CC-BY-ND-3.0", "PUBLIC-DOMAIN-DECLARED")
                .replace("LEGAL_INTERPRETATION", "LICENSE_GRANT");
        assertEquals("FUNDAMENTO_NAO_E_CLAUSULA", json.readTree(call("POST", pub, owner, null,
                pd.replace("EXTRACT_TEXT", "INDEX_SEARCH")).body()).path("code").asText());
        var pdDeclared = call("POST", pub, owner, null, pd.replace("STORE_ORIGINAL", "EXPORT_DERIVATIVES"));
        assertEquals(201, pdDeclared.statusCode(), pdDeclared.body());
        assertEquals("DECLARED_INTERNAL", json.readTree(pdDeclared.body()).path("state").asText());
        assertEquals("PUBLIC-DOMAIN-DECLARED", json.readTree(pdDeclared.body()).path("licenseIdentifier").asText());
        // The owner cannot approve their own declaration; an independent approver can (new decision version).
        String pdecision = API + "/" + pl.path("id").asText() + "/decision";
        String papprove = "{\"outcome\":\"APPROVED\",\"reason\":\"Termos conferidos de forma independente.\",\"operations\":[\"STORE_ORIGINAL\"]}";
        assertEquals(404, call("POST", pdecision, owner, "\"1\"", papprove).statusCode());
        var independent = call("POST", pdecision, approver, "\"1\"", papprove);
        assertEquals(200, independent.statusCode(), independent.body());
        assertEquals("INDEPENDENT", json.readTree(independent.body()).path("decisionBasis").asText());
        assertEquals(1, json.readTree(independent.body()).path("operations").size());
        // Cleanup: the fixture source and licences do not leak into other tests.
        database.transaction(c -> {
            update(c, "SET LOCAL session_replication_role = replica"); // the table is append-only; the fixture is removed as superuser
            update(c, "DELETE FROM app_content_license_declared_ground WHERE license_id IN (SELECT id FROM app_content_license WHERE source_id=?)", SOURCE);
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
                    + ";" + DUAL + ":" + secret + ":" + ORG + ";" + OTHER_STAFF + ":" + secret + ":" + ORG + ";" + OWNER + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.content-licenses.submitters", SUBMITTER + "," + DUAL);
            result.put("carbonflow.platform.content-licenses.approvers", APPROVER + "," + DUAL);
            result.put("carbonflow.platform.content-licenses.owners", OWNER.toString());
            return result;
        }
    }
}
