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

/** D19 slice 2: classification history of canonical models. Own organization: classification rows are append-only. */
@QuarkusTest
@QuarkusTestResource(value = FleetModelClassificationIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class FleetModelClassificationIntegrationTest {
    static final UUID MANAGER = UUID.fromString("ff5d0000-0000-4000-8000-000000000001");
    static final UUID OUTSIDER = UUID.fromString("ff5d0000-0000-4000-8000-000000000002");
    static final UUID ORG = UUID.fromString("ff5d0000-0000-4000-8000-000000000003");
    static final UUID OTHER = UUID.fromString("ff5d0000-0000-4000-8000-000000000004");
    static final byte[] SECRET = "34567890123456789012".getBytes(StandardCharsets.US_ASCII);
    static final String ROOT = "/v1/platform/fleet";

    @Inject PilotDatabase db;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;
    final HttpClient http = HttpClient.newHttpClient();

    @Test
    void classificationIsAppendOnlyEvidencedPendingWhenUnknownAndNeverAdmitsAnything() throws Exception {
        db.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Catálogo teste') ON CONFLICT(id) DO NOTHING", ORG);
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Outra') ON CONFLICT(id) DO NOTHING", OTHER);
            return null;
        });
        String manager = actor(MANAGER), outsider = actor(OUTSIDER);
        verify(manager);
        verify(outsider);
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String body = "{\"organizationId\":\"" + ORG + "\",\"manufacturer\":\"Ficticia\",\"modelName\":\"Mod " + suffix
                + "\",\"variant\":\"Base\",\"vehicleClass\":\"UNKNOWN\",\"propulsion\":\"UNKNOWN\"}";

        // Registered as unknown: explicitly pending, never in the initial cut by default.
        JsonNode model = read(request("POST", ROOT + "/models", manager, key(), body), 201);
        String id = model.path("id").asText();
        assertEquals("PENDING_CLASSIFICATION", model.path("scope").asText());
        assertEquals(0, model.path("classificationVersion").asInt());

        // Another class is registrable (OTHER) and is outside the initial cut, not admitted and not pending.
        JsonNode other = read(request("POST", ROOT + "/models", manager, key(),
                body.replace("Mod " + suffix, "Moto " + suffix).replace("\"vehicleClass\":\"UNKNOWN\"", "\"vehicleClass\":\"OTHER\"")
                        .replace("\"propulsion\":\"UNKNOWN\"", "\"propulsion\":\"BEV\"")), 201);
        assertEquals("OUTSIDE_INITIAL_SCOPE", other.path("scope").asText());

        String known = "{\"vehicleClass\":\"LDV\",\"propulsion\":\"BEV\",\"reason\":\"Conferido na ficha do fabricante.\","
                + "\"evidence\":\"PBEV 2026, linha 62\"}";
        String path = ROOT + "/models/" + id + "/classification";
        assertEquals(401, request("POST", path, null, key(), known).statusCode());
        assertEquals(404, request("POST", path, outsider, key(), known).statusCode());
        assertEquals(404, request("POST", ROOT + "/models/" + UUID.randomUUID() + "/classification", manager, key(), known).statusCode());
        assertEquals(400, request("POST", path, manager, null, known).statusCode());
        assertEquals(422, request("POST", path, manager, key(), known.replace("LDV", "CAR")).statusCode());
        assertEquals(422, request("POST", path, manager, key(), known.replace("\"evidence\":\"PBEV 2026, linha 62\"", "\"evidence\":\"\"")).statusCode());
        assertEquals(422, request("POST", path, manager, key(), known.replace("Conferido na ficha do fabricante.", "curto")).statusCode());
        assertEquals(422, request("POST", path, manager, key(),
                known.replace("\"LDV\"", "\"UNKNOWN\"")).statusCode()); // class unknown with a known propulsion
        assertEquals(409, request("POST", path, manager, key(),
                "{\"vehicleClass\":\"UNKNOWN\",\"propulsion\":\"UNKNOWN\",\"reason\":\"Continua desconhecido.\"}").statusCode());

        String k1 = key();
        JsonNode v1 = read(request("POST", path, manager, k1, known), 200);
        assertEquals("CANDIDATE_INITIAL_SCOPE", v1.path("scope").asText());
        assertEquals(1, v1.path("classificationVersion").asInt());
        assertEquals("LDV", v1.path("vehicleClass").asText());
        assertEquals(v1, read(request("POST", path, manager, k1, known), 200)); // replay
        assertEquals(409, request("POST", path, manager, key(), known).statusCode()); // same as current

        // Reclassifying to another known value appends a version; going back to pending needs only a reason.
        JsonNode v2 = read(request("POST", path, manager, key(),
                known.replace("LDV", "HDV").replace("BEV", "OTHER")), 200);
        assertEquals("OUTSIDE_INITIAL_SCOPE", v2.path("scope").asText());
        assertEquals(2, v2.path("classificationVersion").asInt());
        JsonNode v3 = read(request("POST", path, manager, key(),
                "{\"vehicleClass\":\"UNKNOWN\",\"propulsion\":\"UNKNOWN\",\"reason\":\"Evidência anterior contestada.\"}"), 200);
        assertEquals("PENDING_CLASSIFICATION", v3.path("scope").asText());
        assertEquals(3, v3.path("classificationVersion").asInt());

        // The board shows the effective classification; the history is retained and immutable.
        JsonNode board = read(request("GET", ROOT + "/board", manager, null, null), 200);
        JsonNode shown = null;
        for (var m : board.path("models")) if (m.path("id").asText().equals(id)) shown = m;
        assertNotNull(shown);
        assertEquals("PENDING_CLASSIFICATION", shown.path("scope").asText());
        assertEquals(3L, db.read(c -> first(c, "SELECT count(*) FROM app_fleet_model_classification WHERE model_id=?::uuid",
                r -> r.getLong(1), id)).longValue());
        assertThrows(RuntimeException.class, () -> db.transaction(c -> update(c,
                "UPDATE app_fleet_model_classification SET reason='alterada depois da decisão' WHERE model_id=?::uuid", id)));
        // The original registration is untouched, and an effective view feeds the downstream readers.
        assertEquals("UNKNOWN", db.read(c -> first(c, "SELECT vehicle_class FROM app_fleet_model WHERE id=?::uuid", r -> r.getString(1), id)));
        assertEquals(1L, db.read(c -> first(c, "SELECT count(*) FROM app_fleet_model_effective WHERE id=?::uuid AND classification_version=3",
                r -> r.getLong(1), id)).longValue());
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private String actor(UUID id) {
        String token = AccessSecrets.newToken();
        db.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES(?,?,'Catálogo teste','ACTIVE',statement_timestamp()) ON CONFLICT(id) DO NOTHING
                """, id, id + "@example.invalid");
            update(c, "INSERT INTO app_account_session(token_hash,identity_id,expires_at) VALUES(?,?,statement_timestamp()+interval '1 hour')",
                    AccessSecrets.digest(token), id);
            return null;
        });
        return token;
    }

    private void verify(String token) throws Exception {
        long counter = db.read(c -> first(c, "SELECT floor(extract(epoch from statement_timestamp())/30)::bigint", r -> r.getLong(1)));
        String code = "{\"code\":\"" + ComplianceStaff.codeAt(SECRET, counter) + "\"}";
        assertEquals(200, request("POST", "/v1/compliance/session", token, null, code).statusCode());
    }

    private HttpResponse<String> request(String method, String path, String token, String key, String payload) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (token != null) builder.header("Cookie", AccountAccess.COOKIE + "=" + token);
        if (key != null) builder.header("Idempotency-Key", key);
        if (payload != null) builder.header("Content-Type", "application/json");
        builder.method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(payload));
        return http.send(builder.build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(HttpResponse<String> result, int status) throws Exception {
        assertEquals(status, result.statusCode(), result.body());
        return json.readTree(result.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String, String> start() {
            var values = new HashMap<>(super.start());
            var secret = Base64.getUrlEncoder().withoutPadding().encodeToString(SECRET);
            values.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG + ";" + OUTSIDER + ":" + secret + ":" + OTHER);
            values.put("carbonflow.platform.source-readiness.viewers", MANAGER + "," + OUTSIDER);
            values.put("carbonflow.platform.source-work.managers", MANAGER.toString());
            values.put("carbonflow.platform.fleet-coverage.reviewers", MANAGER.toString());
            return values;
        }
    }
}
