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

/** #94/#95: alias candidates are imported and decided by different people and never touch the canonical catalog. */
@QuarkusTest
@QuarkusTestResource(value = FleetAliasCandidatesIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class FleetAliasCandidatesIntegrationTest {
    private static final UUID IMPORTER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d41");
    private static final UUID REVIEWER = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d42");
    private static final UUID DUAL = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d43");
    private static final UUID OTHER_STAFF = UUID.fromString("b77f1011-84bf-478a-8b86-2b5fd2881d44");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a41");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/fleet/alias-candidates";
    private static final String SHA = "c".repeat(64);

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String item(String name, String key, String level, String matches) {
        return "{\"literalName\":\"" + name + "\",\"recordKey\":\"" + key + "\",\"level\":\"" + level
                + "\",\"reason\":\"Casamento de texto, sem decisão de alias.\",\"matches\":" + matches + "}";
    }

    private static String match(int line, String version) {
        return "{\"brand\":\"BYD\",\"model\":\"DOLPHIN\",\"version\":\"" + version + "\",\"category\":\"Médio\",\"pbevLine\":" + line + "}";
    }

    private static String batch(String period, String sha, String... items) {
        return "{\"observationPeriod\":\"" + period + "\",\"pbevSourceSha256\":\"" + sha + "\",\"items\":["
                + String.join(",", items) + "]}";
    }

    private long count(String table) {
        return database.read(c -> first(c, "SELECT count(*) FROM " + table, r -> r.getLong(1))).longValue();
    }

    @Test
    void candidatesAreImportedOnceDecidedByAnotherPersonAndNeverTouchTheCatalog() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        String nameA = "I/BYD DOLPHIN GS 180EV " + suffix, nameB = "I/RENAULT E-KWID TECHNO " + suffix;
        String itemA = item(nameA, "I_BYD_DOLPHIN_" + suffix, "EXACT", "[" + match(1, "GS 180EV") + "]");
        String itemB = item(nameB, "I_RENAULT_" + suffix, "MODEL_ONLY", "[" + match(10, "INTENS") + "," + match(11, "CARGO") + "]");
        database.transaction(c -> update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        long models = count("app_fleet_model"), aliases = count("app_fleet_model_alias");
        String importer = session(IMPORTER), reviewer = session(REVIEWER), dual = session(DUAL), other = session(OTHER_STAFF);
        assertEquals(401, call("GET", API, null, null, null).statusCode());
        assertEquals(403, call("GET", API, importer, null, null).statusCode());
        for (String who : new String[]{importer, reviewer, dual, other})
            assertEquals(200, call("POST", "/v1/compliance/session", who, null, currentCode()).statusCode());

        // Outside both rosters nothing is visible or possible; the reviewer cannot import, the importer cannot decide.
        assertEquals(404, call("GET", API, other, null, null).statusCode());
        assertEquals(404, call("POST", API, other, null, batch("2026-09", SHA, itemA)).statusCode());
        assertEquals(404, call("POST", API, reviewer, null, batch("2026-09", SHA, itemA)).statusCode());

        // Invalid batches.
        assertEquals(422, call("POST", API, importer, null, batch("2026-13", SHA, itemA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", "abc", itemA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA)).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "NONE", "[" + match(1, "X") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "EXACT", "[]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "chave inválida", "EXACT", "[" + match(1, "X") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, item(nameA, "K", "EXACT", "[" + match(1, "X") + "," + match(1, "Y") + "]"))).statusCode());
        assertEquals(422, call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemA)).statusCode());

        // Import is idempotent per name, period and PBEV file.
        var first = json.readTree(ok(call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemB))).body());
        assertEquals(2, first.path("created").asInt());
        var again = json.readTree(ok(call("POST", API, importer, null, batch("2026-09", SHA, itemA, itemB))).body());
        assertEquals(0, again.path("created").asInt());
        assertEquals(2, again.path("alreadyPresent").asInt());

        // Listing: pending, with the evidence and the limits.
        var pending = json.readTree(ok(call("GET", API + "?state=PENDING", reviewer, null, null)).body());
        String idA = null, idB = null;
        for (var c : pending) {
            if (c.path("literalName").asText().equals(nameA)) idA = c.path("id").asText();
            if (c.path("literalName").asText().equals(nameB)) idB = c.path("id").asText();
        }
        assertNotNull(idA);
        assertNotNull(idB);
        final String acceptedId = idA;
        var shownA = json.readTree(ok(call("GET", API, reviewer, null, null)).body());
        assertEquals("PENDING", find(shownA, idA).path("state").asText());
        assertEquals("EXACT", find(shownA, idA).path("level").asText());
        assertTrue(find(shownA, idA).path("limits").asText().contains("não é alias"));
        assertEquals(2, find(shownA, idB).path("matches").size());
        assertEquals(422, call("GET", API + "?state=OUTRO", reviewer, null, null).statusCode());

        // Decisions: only reviewers, never the importer of the candidate.
        String decisionA = API + "/" + idA + "/decision", decisionB = API + "/" + idB + "/decision";
        String accept = "{\"outcome\":\"ACCEPTED\",\"selectedPbevLine\":1,\"reason\":\"Nome e versão conferidos no PBEV.\"}";
        assertEquals(404, call("POST", decisionA, importer, null, accept).statusCode());
        assertEquals(404, call("POST", API + "/" + UUID.randomUUID() + "/decision", reviewer, null, accept).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("\"selectedPbevLine\":1,", "")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("\"selectedPbevLine\":1", "\"selectedPbevLine\":99")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("Nome e versão conferidos no PBEV.", "curto")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null, accept.replace("ACCEPTED", "APPROVED")).statusCode());
        assertEquals(422, call("POST", decisionA, reviewer, null,
                "{\"outcome\":\"REJECTED\",\"selectedPbevLine\":1,\"reason\":\"Recusado, mas com linha escolhida.\"}").statusCode());

        // Dual custody: someone holding both privileges cannot decide on their own import.
        var own = json.readTree(call("POST", API, dual, null, batch("2026-08", SHA, item(nameA + " próprio", "K_" + suffix, "EXACT", "[" + match(5, "X") + "]"))).body());
        assertEquals(1, own.path("created").asInt());
        var ownList = json.readTree(ok(call("GET", API + "?state=PENDING", dual, null, null)).body());
        String ownId = null;
        for (var c : ownList) if (c.path("literalName").asText().endsWith("próprio")) ownId = c.path("id").asText();
        assertNotNull(ownId);
        assertEquals(403, call("POST", API + "/" + ownId + "/decision", dual, null,
                "{\"outcome\":\"REJECTED\",\"reason\":\"Tentativa da mesma pessoa.\"}").statusCode());

        // Acceptance picks one PBEV line explicitly and is final.
        var accepted = json.readTree(ok(call("POST", decisionA, reviewer, null, accept)).body());
        assertEquals("ACCEPTED", accepted.path("state").asText());
        assertEquals(1, accepted.path("selectedPbevLine").asInt());
        assertEquals(REVIEWER.toString(), accepted.path("decidedBy").asText());
        assertEquals(409, call("POST", decisionA, reviewer, null, accept).statusCode());
        // MODEL_ONLY: the reviewer must name which version, and may also reject.
        var rejected = json.readTree(ok(call("POST", decisionB, reviewer, null,
                "{\"outcome\":\"REJECTED\",\"reason\":\"A versão Techno não existe no PBEV.\"}")).body());
        assertEquals("REJECTED", rejected.path("state").asText());
        assertTrue(rejected.path("selectedPbevLine").isNull());
        assertEquals(1, json.readTree(ok(call("GET", API + "?state=ACCEPTED", reviewer, null, null)).body()).findValues("id").stream()
                .filter(v -> v.asText().equals(acceptedId)).count());

        // The canonical catalog was never touched, and decisions are append-only.
        assertEquals(models, count("app_fleet_model"));
        assertEquals(aliases, count("app_fleet_model_alias"));
        var update = assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c,
                "UPDATE app_fleet_alias_candidate_decision SET reason='alterada depois da decisão' WHERE true")));
        assertNotNull(update);

        // Successful operations left one audit event each: 3 imports + 4 listings + 2 decisions. Refused attempts,
        // including the invalid state filter, roll back or fail before the transaction and leave nothing.
        Long audited = database.read(c -> first(c, "SELECT count(*) FROM app_compliance_access_event WHERE action IN "
                + "('VIEW_FLEET_ALIAS_CANDIDATES','IMPORT_FLEET_ALIAS_CANDIDATES','DECIDE_FLEET_ALIAS_CANDIDATE') "
                + "AND actor_id IN (?,?,?)", r -> r.getLong(1), IMPORTER, REVIEWER, DUAL));
        assertEquals(9L, audited.longValue());
    }

    private static JsonNode find(JsonNode list, String id) {
        for (var item : list) if (item.path("id").asText().equals(id)) return item;
        throw new AssertionError("candidate not listed: " + id);
    }

    private HttpResponse<String> ok(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response;
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
            result.put("carbonflow.compliance.staff", IMPORTER + ":" + secret + ":" + ORG + ";" + REVIEWER + ":" + secret + ":" + ORG
                    + ";" + DUAL + ":" + secret + ":" + ORG + ";" + OTHER_STAFF + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.fleet-alias-candidates.importers", IMPORTER + "," + DUAL);
            result.put("carbonflow.platform.fleet-alias-candidates.reviewers", REVIEWER + "," + DUAL);
            return result;
        }
    }
}
