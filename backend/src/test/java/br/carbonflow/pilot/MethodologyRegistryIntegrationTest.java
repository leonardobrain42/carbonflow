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

/** D18 slice 1: the administration registers methodologies; registering never creates a package or a capability. */
@QuarkusTest
@QuarkusTestResource(value = MethodologyRegistryIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class MethodologyRegistryIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d01");
    private static final UUID OTHER_STAFF = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d02");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a01");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/methodology-registry";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String entry(String edition, String title) {
        return "{\"program\":\"VCS\",\"methodology\":\"METHZ\",\"edition\":\"" + edition + "\",\"title\":\"" + title
                + "\",\"rightsHolder\":\"Titular de teste\",\"officialUri\":\"https://example.org/metodologia\"}";
    }

    @Test
    void registryIsStaffOnlyAppendOnlyAndNeverCreatesAPackage() throws Exception {
        String manager = session(MANAGER), other = session(OTHER_STAFF), plain = session(UUID.randomUUID());
        database.transaction(c -> update(c,
                "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));

        // Access: anonymous, a normal account, staff without the privilege and a manager without MFA are refused.
        assertEquals(401, call("GET", API, null, null, null).statusCode());
        assertTrue(Set(403, 404).contains(call("GET", API, plain, null, null).statusCode()));
        assertEquals(403, call("GET", API, manager, null, null).statusCode());
        assertEquals(200, call("POST", "/v1/compliance/session", manager, null, currentCode()).statusCode());
        assertEquals(200, call("POST", "/v1/compliance/session", other, null, currentCode()).statusCode());
        assertEquals(404, call("GET", API, other, null, null).statusCode());
        assertEquals(404, call("POST", API, other, "\"0\"", entry("1.0", "Metodologia Z")).statusCode());

        // Seed: the first slice is listed with its package; nothing else is.
        var listed = ok(call("GET", API, manager, null, null));
        JsonNode seeded = null;
        for (var item : listed) {
            assertNotEquals("METHZ", item.path("methodology").asText());
            if ("VM0038".equals(item.path("methodology").asText())) seeded = item;
        }
        assertNotNull(seeded, listed.toString());
        assertEquals("1.1", seeded.path("edition").asText());
        assertTrue(seeded.path("hasPackage").asBoolean());
        assertEquals("SEED", seeded.path("origin").asText());

        // Register: new key needs If-Match "0"; the result has no package and no capability.
        var created = call("POST", API, manager, "\"0\"", entry("1.0", "Metodologia Z"));
        assertEquals(201, created.statusCode(), created.body());
        assertEquals("\"1\"", created.headers().firstValue("ETag").orElseThrow());
        JsonNode first = json.readTree(created.body());
        assertEquals(1, first.path("version").asInt());
        assertFalse(first.path("hasPackage").asBoolean());
        assertEquals("ADMIN", first.path("origin").asText());
        assertTrue(first.path("digest").asText().matches("[0-9a-f]{64}"));
        // Same content again: no new version, even with a stale precondition.
        var replay = call("POST", API, manager, "\"0\"", entry("1.0", "Metodologia Z"));
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(1, json.readTree(replay.body()).path("version").asInt());
        assertEquals(1L, rows("METHZ"));
        // Correction: a new version, needing the current version; a stale one is refused and nothing is overwritten.
        assertEquals(428, call("POST", API, manager, null, entry("1.0", "Metodologia Z revisada")).statusCode());
        assertEquals(412, call("POST", API, manager, "\"0\"", entry("1.0", "Metodologia Z revisada")).statusCode());
        var second = call("POST", API, manager, "\"1\"", entry("1.0", "Metodologia Z revisada"));
        assertEquals(201, second.statusCode(), second.body());
        assertEquals(2, json.readTree(second.body()).path("version").asInt());
        assertNotEquals(first.path("digest").asText(), json.readTree(second.body()).path("digest").asText());
        assertEquals(2L, rows("METHZ"));
        assertEquals("Metodologia Z", database.read(c -> first(c,
                "SELECT title FROM app_methodology_registry WHERE methodology='METHZ' AND version=1", r -> r.getString(1))));
        // A different edition is a different key.
        assertEquals(201, call("POST", API, manager, "\"0\"", entry("2.0", "Metodologia Z 2")).statusCode());

        // Validation.
        for (String bad : new String[]{
                entry("1.0", "Metodologia Z").replace("\"VCS\"", "\"vcs\""),
                entry("1.0", "Metodologia Z").replace("METHZ", "m"),
                entry("1.0", "Metodologia Z").replace("\"1.0\"", "\"\""),
                entry("1.0", "ab"),
                entry("1.0", "Metodologia Z").replace("https://", "http://"),
                entry("1.0", "Metodologia\\u0007Z")}) {
            assertEquals(422, call("POST", API, manager, "\"0\"", bad).statusCode(), bad);
        }

        // Append-only: the database refuses changes and deletions.
        assertThrows(Exception.class, () -> database.transaction(c ->
                update(c, "UPDATE app_methodology_registry SET title='x' WHERE methodology='METHZ'")));
        assertThrows(Exception.class, () -> database.transaction(c ->
                update(c, "DELETE FROM app_methodology_registry WHERE methodology='METHZ'")));
        assertTrue(database.read(c -> first(c, "SELECT count(*) FROM app_compliance_access_event WHERE actor_id=? AND action='REGISTER_METHODOLOGY'",
                r -> r.getLong(1), MANAGER)) >= 3);

        // A registered methodology becomes a candidate with an explicit gap, never a package or a fallback.
        assertEquals(201, call("POST", API, manager, "\"0\"", entry("9.9", "Metodologia Y").replace("METHZ", "METHY")).statusCode());
        UUID ownerId = UUID.randomUUID();
        String owner = session(ownerId);
        UUID org = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?, 'SANDBOX','Org projeto')", org);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?, 'DRAFT_OWNER')", org, ownerId);
            return null;
        });
        var project = call("POST", "/v1/projects", owner, null, "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto\"}",
                UUID.randomUUID().toString());
        assertEquals(201, project.statusCode(), project.body());
        String root = "/v1/projects/" + json.readTree(project.body()).path("projectId").asText();

        JsonNode triage = ok(call("GET", root + "/triage", owner, null, null));
        assertEquals("NONE", triage.at("/candidateStatus/state").asText());
        long vm = 0, registered = 0;
        for (var option : triage.path("methodologies")) {
            if ("VM0038".equals(option.path("value").asText())) vm++;
            if ("METHY V9.9".equals(option.path("value").asText())) registered++;
        }
        assertEquals(1, vm);
        assertEquals(1, registered);

        var saved = call("PUT", root + "/triage/steps/1", owner, "\"0\"",
                "{\"answers\":{\"objective\":\"Teste\",\"activityType\":\"OUTRO\",\"methodologyCandidate\":\"METHY V9.9\"},\"nextStep\":2}",
                UUID.randomUUID().toString());
        assertEquals(200, saved.statusCode(), saved.body());
        JsonNode view = json.readTree(saved.body());
        assertEquals("REGISTERED_NO_PACKAGE", view.at("/candidateStatus/state").asText());
        assertEquals("METHY", view.at("/candidateStatus/methodology").asText());
        assertEquals("GENERAL", view.at("/journey/id").asText());
        assertEquals("SEM_JORNADA_CONFIGURADA", view.at("/guidance/state").asText());

        JsonNode readiness = ok(call("GET", root + "/technical/open-calculation-readiness", owner, null, null));
        assertEquals(1, readiness.path("gates").size());
        assertEquals("METHODOLOGY", readiness.at("/gates/0/code").asText());
        assertTrue(readiness.path("engine").isNull());
        assertFalse(readiness.path("canRunE8").asBoolean());
        // A customer never sees the administrative registry.
        assertTrue(Set(401, 403, 404).contains(call("GET", API, owner, null, null).statusCode()));
    }

    private static java.util.Set<Integer> Set(Integer... values) {
        return java.util.Set.of(values);
    }

    private long rows(String methodology) {
        return database.read(c -> first(c, "SELECT count(*) FROM app_methodology_registry WHERE methodology=?",
                r -> r.getLong(1), methodology));
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
            return result;
        }
    }
}
