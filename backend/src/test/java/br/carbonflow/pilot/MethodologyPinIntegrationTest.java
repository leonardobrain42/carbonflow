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

/** D18 slice 3: the project pins registry/package versions; capability is derived per purpose and never enables a gate. */
@QuarkusTest
@QuarkusTestResource(value = MethodologyPinIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class MethodologyPinIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d21");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a21");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/methodology-registry";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private JsonNode save(String root, String owner, long version, String candidate) throws Exception {
        var response = call("PUT", root + "/triage/steps/1", owner, "\"" + version + "\"",
                "{\"answers\":{\"objective\":\"Teste\",\"activityType\":\"OUTRO\",\"methodologyCandidate\":"
                + (candidate == null ? "null" : "\"" + candidate + "\"") + "},\"nextStep\":2}",
                UUID.randomUUID().toString());
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    private static JsonNode purpose(JsonNode view, String code) {
        for (var item : view.path("capabilities")) if (code.equals(item.path("purpose").asText())) return item;
        fail("missing purpose " + code);
        return null;
    }

    @Test
    void pinIsAppendOnlyNeverFollowsNewVersionsAndCapabilityIsDerived() throws Exception {
        String manager = session(MANAGER);
        database.transaction(c -> update(c,
                "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        assertEquals(200, call("POST", "/v1/compliance/session", manager, null, currentCode()).statusCode());
        String methp = "{\"program\":\"VCS\",\"methodology\":\"METHP\",\"edition\":\"1.0\",\"title\":\"Metodologia P\","
                + "\"rightsHolder\":\"Titular\"}";
        assertEquals(201, call("POST", API, manager, "\"0\"", methp).statusCode());

        UUID ownerId = UUID.randomUUID(), strangerId = UUID.randomUUID();
        String owner = session(ownerId), stranger = session(strangerId);
        UUID org = UUID.randomUUID(), otherOrg = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?, 'SANDBOX','Org projeto')", org);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?, 'DRAFT_OWNER')", org, ownerId);
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?, 'SANDBOX','Outra org')", otherOrg);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?, 'DRAFT_OWNER')", otherOrg, strangerId);
            return null;
        });
        var created = call("POST", "/v1/projects", owner, null, "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto\"}",
                UUID.randomUUID().toString());
        assertEquals(201, created.statusCode(), created.body());
        String id = json.readTree(created.body()).path("projectId").asText();
        String root = "/v1/projects/" + id;

        // No candidate: no pin, and only the triage purpose differs from the closed gates.
        JsonNode empty = ok(call("GET", root + "/triage", owner, null, null));
        assertTrue(empty.path("methodologyPin").isNull());
        assertEquals("NOT_ENABLED", purpose(empty, "TRIAGE").path("effective").asText());

        // VM0038: pin with registry and package versions; capability follows code, data and licence.
        JsonNode first = save(root, owner, 0, "VM0038");
        JsonNode pin = first.path("methodologyPin");
        assertEquals(1, pin.path("version").asInt());
        assertEquals("VM0038", pin.path("methodology").asText());
        assertEquals("1.1", pin.path("edition").asText());
        assertEquals(1, pin.path("registryVersion").asInt());
        assertEquals(64, pin.path("registryDigest").asText().length());
        assertEquals(MethodologyPackages.current().packages().get(0).digest(), pin.path("packageDigest").asText());
        assertFalse(pin.path("newerRegistryVersion").asBoolean());
        assertFalse(pin.path("packageChanged").asBoolean());
        assertEquals("ENABLED", purpose(first, "TRIAGE").path("effective").asText());
        assertEquals("MET", purpose(first, "TRIAGE").at("/code/state").asText());
        assertEquals("NOT_REQUIRED", purpose(first, "TRIAGE").at("/data/state").asText());
        // No licence decision exists in this database: reference is not enabled; the gates stay closed.
        assertEquals("NOT_ENABLED", purpose(first, "SOURCE_REFERENCE").path("effective").asText());
        assertEquals("MET", purpose(first, "SOURCE_REFERENCE").at("/code/state").asText());
        assertEquals("NOT_MET", purpose(first, "SOURCE_REFERENCE").at("/licence/state").asText());
        assertEquals("NOT_ENABLED", purpose(first, "CALCULATION_ADMISSION").path("effective").asText());
        assertEquals("NOT_ENABLED", purpose(first, "EXTERNAL_ACTS").path("effective").asText());
        // The customer view never carries the administrative inventory.
        String serialised = first.path("capabilities").toString();
        assertFalse(serialised.contains("sourceNeeds"));
        assertFalse(serialised.contains("needCount"));

        // Saving again without a change does not add a pin.
        JsonNode again = save(root, owner, first.path("version").asLong(), "VM0038");
        assertEquals(1, again.at("/methodologyPin/version").asInt());

        // Registered methodology without package: a new pin with registry version and no package.
        JsonNode second = save(root, owner, again.path("version").asLong(), "METHP V1.0");
        assertEquals(2, second.at("/methodologyPin/version").asInt());
        assertEquals("METHP", second.at("/methodologyPin/methodology").asText());
        assertTrue(second.at("/methodologyPin/packageId").isNull());
        assertEquals("NOT_ENABLED", purpose(second, "TRIAGE").path("effective").asText());
        assertEquals("NOT_MET", purpose(second, "TRIAGE").at("/code/state").asText());

        // A newer registry version does not change the pinned project; it only raises the flag.
        assertEquals(201, call("POST", API, manager, "\"1\"", methp.replace("Metodologia P", "Metodologia P revisada")).statusCode());
        JsonNode drift = ok(call("GET", root + "/triage", owner, null, null)).path("methodologyPin");
        assertEquals(2, drift.path("version").asInt());
        assertEquals(1, drift.path("registryVersion").asInt());
        assertTrue(drift.path("newerRegistryVersion").asBoolean());
        Long pins = database.read(c -> first(c,
                "SELECT count(*) FROM app_project_methodology_pin WHERE project_id=?", r -> r.getLong(1), id));
        assertEquals(2L, pins.longValue());

        // Saving the SAME candidate (or just moving) while a newer version exists must not re-pin the project:
        // only an explicit migration moves it to the new version.
        JsonNode resaved = save(root, owner, second.path("version").asLong(), "METHP V1.0");
        assertEquals(2, resaved.at("/methodologyPin/version").asInt());
        assertEquals(1, resaved.at("/methodologyPin/registryVersion").asInt());
        assertTrue(resaved.at("/methodologyPin/newerRegistryVersion").asBoolean());

        // Explicit migration: needs the pin version, a confirmation, project rights and a real difference.
        String migrate = root + "/triage/methodology-pin/migrate";
        String confirm = "{\"confirm\":true}";
        assertEquals(428, call("POST", migrate, owner, null, confirm).statusCode());
        assertEquals(412, call("POST", migrate, owner, "\"1\"", confirm).statusCode());
        assertEquals(400, call("POST", migrate, owner, "\"2\"", "{\"confirm\":false}").statusCode());
        assertEquals(400, call("POST", migrate, owner, "\"2\"", "{}").statusCode());
        assertTrue(java.util.Set.of(403, 404).contains(call("POST", migrate, stranger, "\"2\"", confirm).statusCode()));
        assertEquals(401, call("POST", migrate, null, "\"2\"", confirm).statusCode());
        var migrated = call("POST", migrate, owner, "\"2\"", confirm);
        assertEquals(200, migrated.statusCode(), migrated.body());
        assertEquals("\"3\"", migrated.headers().firstValue("ETag").orElseThrow());
        JsonNode moved = json.readTree(migrated.body()).path("methodologyPin");
        assertEquals(3, moved.path("version").asInt());
        assertEquals("MIGRATION", moved.path("origin").asText());
        assertEquals(2, moved.path("migratedFrom").asInt());
        assertEquals(2, moved.path("registryVersion").asInt());
        assertFalse(moved.path("newerRegistryVersion").asBoolean());
        assertEquals("METHP", moved.path("methodology").asText());
        // The pin it replaced is untouched (append-only lineage), and a repeat has nothing to migrate.
        Long oldRegistry = database.read(c -> first(c,
                "SELECT registry_version FROM app_project_methodology_pin WHERE project_id=? AND pin_version=2",
                r -> r.getLong(1), id));
        assertEquals(1L, oldRegistry.longValue());
        assertEquals(409, call("POST", migrate, owner, "\"3\"", confirm).statusCode());
        assertEquals(412, call("POST", migrate, owner, "\"2\"", confirm).statusCode());
        // Saving the same candidate afterwards still does not add pins.
        JsonNode after = save(root, owner, resaved.path("version").asLong(), "METHP V1.0");
        assertEquals(3, after.at("/methodologyPin/version").asInt());

        // Clearing the candidate is recorded; the pin in force then names no methodology.
        JsonNode cleared = save(root, owner, after.path("version").asLong(), null);
        assertEquals(4, cleared.at("/methodologyPin/version").asInt());
        // With no pinned methodology there is nothing to migrate.
        assertEquals(409, call("POST", migrate, owner, "\"4\"", confirm).statusCode());
        assertTrue(cleared.at("/methodologyPin/methodology").isNull());

        // Pins are append-only and isolated to the project.
        assertThrows(RuntimeException.class, () -> database.transaction(c ->
                update(c, "UPDATE app_project_methodology_pin SET methodology=NULL WHERE project_id=?", id)));
        assertThrows(RuntimeException.class, () -> database.transaction(c ->
                update(c, "DELETE FROM app_project_methodology_pin WHERE project_id=?", id)));
        assertTrue(java.util.Set.of(403, 404).contains(call("GET", root + "/triage", stranger, null, null).statusCode()));
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
            result.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.methodology-registry.managers", MANAGER.toString());
            return result;
        }
    }
}
