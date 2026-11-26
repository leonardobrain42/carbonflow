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
import java.util.ArrayList;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/** Roteiro #124 PR 2: synthetic hourly series, licence gate, review bases and nothing admitted. */
@QuarkusTest
@QuarkusTestResource(value = GridSeriesIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class GridSeriesIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("d99f3033-95cf-489a-9c97-3ce3992e2e51");
    private static final UUID REVIEWER = UUID.fromString("d99f3033-95cf-489a-9c97-3ce3992e2e52");
    private static final UUID OWNER = UUID.fromString("d99f3033-95cf-489a-9c97-3ce3992e2e53");
    private static final UUID OUTSIDER = UUID.fromString("d99f3033-95cf-489a-9c97-3ce3992e2e54");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a43");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/grid-series";
    private static final String HASH = "9".repeat(64);
    private static final String SOURCE = "GRIDTEST-SRC";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    /** Two synthetic days of 24 hours in UTC; the last hour of day 2 has no UTC instant (a declared-ambiguous label). */
    private String payload(String source, String edition, String sha, String tz, String value) {
        var records = new ArrayList<String>();
        for (int day = 1; day <= 2; day++)
            for (int hour = 1; hour <= 24; hour++) {
                boolean issue = day == 2 && hour == 24;
                String start = String.format("2026-01-%02dT%02d:00:00Z", day, hour - 1);
                String end = hour == 24 ? String.format("2026-01-%02dT00:00:00Z", day + 1) : String.format("2026-01-%02dT%02d:00:00Z", day, hour);
                String v = day == 1 && hour == 1 ? value : day == 2 && hour == 2 ? "0.5439000000000001" : "0." + (100 + day * 24 + hour);
                records.add("{\"cell\":\"C" + (50 + hour) + "\",\"month\":1,\"day\":" + day + ",\"hourLabel\":" + hour
                        + ",\"valueOriginal\":\"" + v + "\",\"value\":\"" + v + "\",\"unit\":\"tCO2/MWh\","
                        + "\"intervalStartLocal\":\"" + start.replace("Z", "+00:00") + "\","
                        + (issue ? "\"intervalStartUtc\":null,\"intervalEndUtc\":null,\"timeIssue\":\"AMBIGUOUS\"}"
                        : "\"intervalStartUtc\":\"" + start + "\",\"intervalEndUtc\":\"" + end + "\",\"timeIssue\":null}"));
            }
        return "{\"sourceId\":\"" + source + "\",\"edition\":\"" + edition + "\",\"original\":{\"fileSha256\":\"" + sha
                + "\",\"gas\":\"CO2\",\"year\":2026},\"parameters\":{\"timezone\":\"" + tz
                + "\",\"hourConvention\":\"HOUR_ENDING_1_24\",\"unit\":\"tCO2/MWh\",\"targetUnit\":\"tCO2/MWh\"},"
                + "\"quality\":{\"months\":{\"1\":{\"state\":\"PARTIAL\"}}},\"records\":[" + String.join(",", records) + "]}";
    }

    private static String review(String basis, String outcome) {
        return "{\"basis\":\"" + basis + "\",\"outcome\":\"" + outcome + "\",\"reason\":\"Conferido contra a planilha sintética.\"}";
    }

    @Test
    void importIsGatedByTheLicenceStoresOriginalsAndNeverAdmitsAnything() throws Exception {
        var page = call("GET", "/workspace/platform/grid-series", null, null);
        assertEquals(200, page.statusCode());
        assertTrue(page.headers().firstValue("Content-Type").orElse("").startsWith("text/html"));
        UUID submitter = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG);
            update(c, "INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin,sha256) "
                    + "VALUES (?,'e1','EXTERNAL_SOURCE','Fonte sintética de série horária','Titular de teste','EXTERNAL',?)", SOURCE, HASH);
            update(c, "INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin,sha256) "
                    + "VALUES (?,'e2','EXTERNAL_SOURCE','Fonte sintética sem licença','Titular de teste','EXTERNAL',?)", SOURCE, HASH);
            update(c, "INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin,sha256) "
                    + "VALUES (?,'e3','EXTERNAL_SOURCE','Fonte sintética só com cópia','Titular de teste','EXTERNAL',?)", SOURCE, HASH);
            update(c, "INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at) VALUES(?,?,'Conta de teste','ACTIVE',statement_timestamp())",
                    submitter, submitter + "@example.invalid");
            return null;
        });
        String manager = session(MANAGER), reviewer = session(REVIEWER), owner = session(OWNER), outsider = session(OUTSIDER);
        for (String who : new String[]{manager, reviewer, owner, outsider}) verify(who);
        assertEquals(401, call("GET", API, null, null).statusCode());
        assertEquals(404, call("GET", API, outsider, null).statusCode());
        assertEquals(404, call("POST", API, outsider, payload(SOURCE, "e1", HASH, "UTC", "0.2425")).statusCode());
        assertEquals(403, call("POST", API, reviewer, payload(SOURCE, "e1", HASH, "UTC", "0.2425")).statusCode()); // reviews only

        // No licence: only this operation is blocked.
        var blocked = call("POST", API, manager, payload(SOURCE, "e2", HASH, "UTC", "0.2425"));
        assertEquals(409, blocked.statusCode());
        assertEquals("LICENCA_AUSENTE_PARA_OPERACAO", json.readTree(blocked.body()).path("code").asText());
        // A licence that covers only STORE_ORIGINAL does not cover extracting the values.
        license("e3", "DECLARED_INTERNAL", "STORE_ORIGINAL", submitter);
        assertEquals(409, call("POST", API, manager, payload(SOURCE, "e3", HASH, "UTC", "0.2425")).statusCode());
        // An internal declaration covering EXTRACT_TEXT enables the import (and records that it is not independent).
        license("e1", "DECLARED_INTERNAL", "EXTRACT_TEXT", submitter);

        // Validation.
        assertEquals(422, call("POST", API, manager, payload(SOURCE, "e1", HASH, "Marte/Olympus", "0.2425")).statusCode());
        assertEquals(422, call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "-1")).statusCode());
        assertEquals(422, call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "abc")).statusCode());
        assertEquals(422, call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "0.2425").replace("\"CO2\"", "\"CO2e\"")).statusCode());
        assertEquals(422, call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "0.2425").replace("HOUR_ENDING_1_24", "QUALQUER")).statusCode());
        assertEquals(422, call("POST", API, manager, payload("FONTE-QUE-NAO-EXISTE", "e1", HASH, "UTC", "0.2425")).statusCode());
        // The workbook hash must be the one registered for the edition.
        var wrong = call("POST", API, manager, payload(SOURCE, "e1", "8".repeat(64), "UTC", "0.2425"));
        assertEquals("ARQUIVO_NAO_CORRESPONDE_A_EDICAO", json.readTree(wrong.body()).path("code").asText());

        var created = call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "0.2425"));
        assertEquals(201, created.statusCode(), created.body());
        JsonNode series = json.readTree(created.body());
        String id = series.path("id").asText();
        assertEquals("CANDIDATE_NOT_ADMITTED", series.path("state").asText());
        assertFalse(series.path("usableForE8").asBoolean());
        assertEquals("NOT_EVALUATED", series.path("eligibilityVt0011Tool07").asText());
        assertEquals("CO2", series.path("gas").asText());
        assertEquals("OWNER_INTERNAL", series.path("licenseBasis").asText());
        assertEquals("DECLARED_BY_PEOPLE", series.path("timeConventionSource").asText());
        assertEquals(48, series.path("rowCount").asInt());
        assertEquals(1, series.path("timeIssueCount").asInt());
        assertEquals("NOT_BOUND", series.path("v60Binding").path("state").asText());
        assertTrue(series.path("limits").asText().contains("nada aqui é admitido"));
        // The same series and time parameters are not imported twice; a different time zone is a different fact.
        assertEquals("SERIE_JA_IMPORTADA", json.readTree(call("POST", API, manager, payload(SOURCE, "e1", HASH, "UTC", "0.2425")).body()).path("code").asText());
        assertEquals(201, call("POST", API, owner, payload(SOURCE, "e1", HASH, "America/Sao_Paulo", "0.2425")).statusCode());

        // Originals are kept exactly; the ambiguous hour has no UTC instant.
        var values = ok(call("GET", API + "/" + id + "/values?month=1", reviewer, null));
        assertEquals(48, values.size());
        assertEquals("0.2425", values.get(0).path("valueOriginal").asText());
        assertEquals("2026-01-01T00:00:00Z", values.get(0).path("intervalStartUtc").asText());
        assertTrue(values.get(47).path("intervalStartUtc").isNull());
        assertEquals("0.5439000000000001", values.get(25).path("valueOriginal").asText()); // the literal of the workbook, float artefact included
        assertEquals("AMBIGUOUS", values.get(47).path("timeIssue").asText());
        assertEquals(0, ok(call("GET", API + "/" + id + "/values?month=2", reviewer, null)).size());
        assertEquals(422, call("GET", API + "/" + id + "/values?month=13", reviewer, null).statusCode());

        // Reviews: independent excludes the importer; owner-internal is a different fact.
        String rev = API + "/" + id + "/reviews";
        assertEquals(403, call("POST", rev, manager, review("INDEPENDENT", "CHECKED")).statusCode()); // not a reviewer
        assertEquals(403, call("POST", rev, reviewer, review("OWNER_INTERNAL", "CHECKED")).statusCode()); // not an owner
        assertEquals(422, call("POST", rev, reviewer, review("INDEPENDENT", "ADMITTED")).statusCode());
        var internal = json.readTree(ok2(call("POST", rev, owner, review("OWNER_INTERNAL", "CHECKED"))).body());
        assertEquals("CHECKED", internal.path("internalReview").path("outcome").asText());
        assertTrue(internal.path("independentReview").isNull());
        var independent = json.readTree(ok2(call("POST", rev, reviewer, review("INDEPENDENT", "CHECKED"))).body());
        assertEquals("CHECKED", independent.path("independentReview").path("outcome").asText());
        assertEquals("CANDIDATE_NOT_ADMITTED", independent.path("state").asText());
        assertFalse(independent.path("usableForE8").asBoolean());
        assertEquals(404, call("POST", API + "/" + UUID.randomUUID() + "/reviews", reviewer, review("INDEPENDENT", "CHECKED")).statusCode());

        // The listing is audited and append-only.
        var listing = ok(call("GET", API, owner, null));
        assertTrue(listing.path("canImport").asBoolean());
        assertEquals(2, listing.path("editions").size());
        assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c, "DELETE FROM app_grid_series_value WHERE edition_id=?::uuid", id)));
        assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c, "UPDATE app_grid_series_edition SET usable_for_e8=true WHERE id=?::uuid", id)));
    }

    private void license(String edition, String outcome, String operation, UUID submitter) {
        UUID id = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_content_license(id,source_id,edition,submitted_by,environment,purpose,authorization_ref,proof_sha256,starts_at) "
                    + "VALUES (?,?,?,?,'SANDBOX','AUTHORIZED_PROCESSING','Licença sintética de teste',?,'2026-01-01T00:00:00Z')", id, SOURCE, edition, submitter, HASH);
            update(c, "INSERT INTO app_content_license_decision(license_id,version,outcome,decided_by,reason) VALUES (?,1,?,?,'Declaração sintética de teste.')", id, outcome, submitter);
            update(c, "INSERT INTO app_content_license_capability(license_id,decision_version,operation) VALUES (?,1,?)", id, operation);
            return null;
        });
    }

    private HttpResponse<String> ok2(HttpResponse<String> response) {
        assertEquals(200, response.statusCode(), response.body());
        return response;
    }

    private JsonNode ok(HttpResponse<String> response) throws Exception {
        assertEquals(200, response.statusCode(), response.body());
        return json.readTree(response.body());
    }

    /** A one-time code cannot be replayed, so a session already verified by an earlier test is reused. */
    private void verify(String who) throws Exception {
        var current = call("GET", "/v1/compliance/session", who, null, null);
        if (current.statusCode() == 200 && json.readTree(current.body()).path("verified").asBoolean()) return;
        assertEquals(200, call("POST", "/v1/compliance/session", who, null, currentCode()).statusCode());
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

    private HttpResponse<String> call(String method, String path, String token, String body) throws Exception {
        return call(method, path, token, null, body, null);
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
            result.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG + ";" + REVIEWER + ":" + secret + ":" + ORG
                    + ";" + OWNER + ":" + secret + ":" + ORG + ";" + OUTSIDER + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.grid-series.managers", MANAGER.toString());
            result.put("carbonflow.platform.grid-series.reviewers", REVIEWER.toString());
            result.put("carbonflow.platform.grid-series.owners", OWNER.toString());
            return result;
        }
    }
}
