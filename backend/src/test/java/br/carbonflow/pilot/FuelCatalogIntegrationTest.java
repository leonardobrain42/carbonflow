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

/** D19 slice 5 / Q12: the fuel catalog registers and reviews on separate bases and never admits anything. */
@QuarkusTest
@QuarkusTestResource(value = FuelCatalogIntegrationTest.Settings.class, restrictToAnnotatedClass = true)
class FuelCatalogIntegrationTest {
    private static final UUID MANAGER = UUID.fromString("c88f2022-95cf-489a-9c97-3ce3992e2e51");
    private static final UUID REVIEWER = UUID.fromString("c88f2022-95cf-489a-9c97-3ce3992e2e52");
    private static final UUID DUAL = UUID.fromString("c88f2022-95cf-489a-9c97-3ce3992e2e53");
    private static final UUID OWNER = UUID.fromString("c88f2022-95cf-489a-9c97-3ce3992e2e54");
    private static final UUID OUTSIDER = UUID.fromString("c88f2022-95cf-489a-9c97-3ce3992e2e55");
    private static final UUID ORG = UUID.fromString("71e71a49-e926-4eaa-aab8-86b04add8a42");
    private static final byte[] OTP = "78901234567890123456".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/platform/fuel-catalog";
    private static final String EVIDENCE = "\"evidenceSource\":\"Fonte sintética de teste\",\"evidenceLocator\":\"tabela 1\",\"readState\":\"PRIMARY_READ\"";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    private static String datum(String fuel, String parameter, String gas, String scope, String gwp, String value, String unit,
                                String from, String to, String extra) {
        return "{\"kind\":\"DATUM\",\"fuelId\":\"" + fuel + "\",\"parameter\":\"" + parameter + "\""
                + (gas == null ? "" : ",\"gas\":\"" + gas + "\"") + (scope == null ? "" : ",\"emissionScope\":\"" + scope + "\"")
                + (gwp == null ? "" : ",\"gwpSet\":\"" + gwp + "\"") + ",\"value\":\"" + value + "\",\"unit\":\"" + unit
                + "\",\"vintageYear\":2024,\"validFrom\":\"" + from + "\"" + (to == null ? "" : ",\"validTo\":\"" + to + "\"")
                + "," + EVIDENCE + (extra == null ? "" : "," + extra) + "}";
    }

    private static String composition(String blend, String component, String nominal, String from, String to) {
        return "{\"kind\":\"COMPOSITION\",\"fuelId\":\"" + blend + "\",\"componentId\":\"" + component
                + "\",\"compositionBasis\":\"VOLUME_FRACTION\"," + (nominal == null ? "\"remainder\":true" : "\"nominal\":\"" + nominal + "\"")
                + ",\"validFrom\":\"" + from + "\"" + (to == null ? "" : ",\"validTo\":\"" + to + "\"") + "," + EVIDENCE + "}";
    }

    private static String review(String basis, String outcome) {
        return "{\"basis\":\"" + basis + "\",\"outcome\":\"" + outcome + "\",\"reason\":\"Conferido contra a fonte sintética.\"}";
    }

    private static String fuel(String id, String role) {
        return "{\"id\":\"" + id + "\",\"name\":\"Combustível de teste\",\"family\":\"OTHER\",\"role\":\"" + role
                + "\",\"phase\":\"LIQUID\",\"pending\":[\"tudo\"]}";
    }

    @Test
    void catalogRegistersAndReviewsOnSeparateBasesAndNeverAdmitsAnything() throws Exception {
        assertEquals(200, call("GET", "/workspace/platform/fuel-catalog", null, null).statusCode()); // SPA deep link
        database.transaction(c -> update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Organização de teste') ON CONFLICT(id) DO NOTHING", ORG));
        String suffix = UUID.randomUUID().toString().substring(0, 8).toUpperCase().replace('-', 'A');
        String product = "TP" + suffix, blend = "TB" + suffix, comp = "TC" + suffix;
        String manager = session(MANAGER), reviewer = session(REVIEWER), dual = session(DUAL), owner = session(OWNER), outsider = session(OUTSIDER);
        assertEquals(401, call("GET", API, null, null).statusCode());
        assertEquals(403, call("GET", API, manager, null).statusCode());
        for (String who : new String[]{manager, reviewer, dual, owner, outsider}) verify(who);
        assertEquals(404, call("GET", API, outsider, null).statusCode());

        var seen = json.readTree(ok(call("GET", API, manager, null)).body());
        assertEquals("NOT_ADMITTED", seen.path("admission").asText());
        assertTrue(seen.path("canRegister").asBoolean());
        assertFalse(seen.path("canReviewIndependent").asBoolean());
        assertTrue(seen.path("fuels").size() >= 8);
        assertTrue(seen.toString().contains("GASOLINE_C"));

        // Fuels: only managers/owners, vocabulary checked, duplicates refused.
        assertEquals(404, call("POST", API + "/fuels", outsider, fuel(product, "PRODUCT")).statusCode());
        assertEquals(404, call("POST", API + "/fuels", reviewer, fuel(product, "PRODUCT")).statusCode());
        assertEquals(422, call("POST", API + "/fuels", manager, fuel("minusculo", "PRODUCT")).statusCode());
        assertEquals(422, call("POST", API + "/fuels", manager, fuel(product, "OUTRO")).statusCode());
        ok(call("POST", API + "/fuels", manager, fuel(product, "PRODUCT")));
        ok(call("POST", API + "/fuels", manager, fuel(blend, "BLEND")));
        ok(call("POST", API + "/fuels", manager, fuel(comp, "PRODUCT")));
        assertEquals(409, call("POST", API + "/fuels", manager, fuel(product, "PRODUCT")).statusCode());

        // Compositions: blends only, sums never exceed 1, one remainder, windows identical or disjoint.
        assertEquals(422, call("POST", API + "/entries", manager, composition(product, comp, "0.3", "2026-08-01", null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, composition(blend, comp, "1.2", "2026-08-01", null)).statusCode());
        ok(call("POST", API + "/entries", manager, composition(blend, comp, "0.32", "2026-08-01", null)));
        assertEquals(409, call("POST", API + "/entries", manager, composition(blend, comp, "0.1", "2026-08-01", null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, composition(blend, product, "0.7", "2026-08-01", null)).statusCode());
        ok(call("POST", API + "/entries", manager, composition(blend, product, null, "2026-08-01", null)));
        assertEquals(409, call("POST", API + "/entries", manager, composition(blend, blend.equals(comp) ? product : comp, null, "2026-08-01", null)).statusCode());
        assertEquals(409, call("POST", API + "/entries", manager, composition(blend, comp, "0.3", "2026-06-01", "2026-09-01")).statusCode());
        ok(call("POST", API + "/entries", manager, composition(blend, comp, "0.3", "2025-01-01", "2026-07-31")));

        // Data points: gas, scope and GWP are explicit; the unit label must agree with the gas; series do not overlap.
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", null, "TTW", null, "3", "kgCO2/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2E_AGGREGATE", "TTW", null, "3", "kgCO2e/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", "AR5_100", "3", "kgCO2/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", null, "3", "kgCO2e/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", null, "-3", "kgCO2/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "LHV", null, null, null, "30", "kg/L", "2024-01-01", null, null)).statusCode());
        assertEquals(422, call("POST", API + "/entries", manager, datum(product, "DENSITY", "CO2_FOSSIL", null, null, "0.7", "kg/L", "2024-01-01", null, null)).statusCode());
        var ef = json.readTree(ok(call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", null, "3", "kgCO2/L",
                "2024-01-01", "2024-12-31", "\"ipccReference\":\"Referência sintética\",\"ipccValue\":\"2.4\",\"ipccUnit\":\"kgCO2/L\""))).body());
        String efId = ef.path("id").asText();
        assertEquals("COMPARED_SAME_UNIT", ef.path("qaqc").path("state").asText());
        assertEquals("0.250000", ef.path("qaqc").path("relativeDeviation").asText());
        assertEquals("3", ef.path("value").asText());
        assertEquals("kgCO2/L", ef.path("unit").asText());
        assertEquals("NOT_ADMITTED", ef.path("admission").asText());
        assertEquals(409, call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "TTW", null, "3.1", "kgCO2/L", "2024-12-31", null, null)).statusCode());
        ok(call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2_FOSSIL", "WTW", null, "3.5", "kgCO2/L", "2024-01-01", null, null)));
        ok(call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CO2E_AGGREGATE", "TTW", "AR5_100", "3.1", "kgCO2e/L", "2024-01-01", null, null)));
        var mismatch = json.readTree(ok(call("POST", API + "/entries", manager, datum(product, "EMISSION_FACTOR", "CH4", "TTW", null, "0.001", "kgCH4/L",
                "2024-01-01", null, "\"ipccReference\":\"Referência sintética\",\"ipccValue\":\"1\",\"ipccUnit\":\"gCH4/MJ\""))).body());
        assertEquals("COMPARED_UNIT_MISMATCH", mismatch.path("qaqc").path("state").asText());

        // Conversion needs a reviewed LHV that covers the factor; the gas never changes.
        String conv = API + "/entries/" + efId + "/conversion?targetUnit=";
        assertEquals(422, call("GET", conv + "gCO2/MJ", manager, null).statusCode());
        var lhv = json.readTree(ok(call("POST", API + "/entries", manager, datum(product, "LHV", null, null, null, "30", "MJ/L", "2020-01-01", null, null))).body());
        assertEquals(422, call("GET", conv + "gCO2/MJ", manager, null).statusCode()); // present but unreviewed
        ok(call("POST", API + "/entries/" + lhv.path("id").asText() + "/reviews", owner, review("OWNER_INTERNAL", "NEEDS_INFORMATION")));
        assertEquals(422, call("GET", conv + "gCO2/MJ", manager, null).statusCode());
        ok(call("POST", API + "/entries/" + lhv.path("id").asText() + "/reviews", owner, review("OWNER_INTERNAL", "REVIEWED_OK")));
        var converted = json.readTree(ok(call("GET", conv + "gCO2/MJ", manager, null)).body());
        assertEquals("100", converted.path("value").asText());
        assertEquals("kgCO2/L", converted.path("originalUnit").asText());
        assertEquals("3", converted.path("originalValue").asText());
        assertEquals(lhv.path("id").asText(), converted.path("steps").get(converted.path("steps").size() - 1).path("entryId").asText());
        assertEquals("NOT_ADMITTED", converted.path("admission").asText());
        assertEquals("11.356235352", json.readTree(ok(call("GET", conv + "kgCO2/USgal", manager, null)).body()).path("value").asText());
        assertEquals("3000", json.readTree(ok(call("GET", conv + "gCO2/L", manager, null)).body()).path("value").asText());
        assertEquals(422, call("GET", conv + "kgCO2e/L", manager, null).statusCode());
        assertEquals(422, call("GET", conv + "kgCO2/kg", manager, null).statusCode()); // no density registered
        assertEquals(422, call("GET", conv + "lixo", manager, null).statusCode());
        assertEquals(404, call("GET", conv + "gCO2/MJ", outsider, null).statusCode());

        // Reviews: independent never by the registrant, internal only by owners, bases never substitute each other.
        String own = json.readTree(ok(call("POST", API + "/entries", dual, datum(product, "DENSITY", null, null, null, "0.74", "kg/L", "2020-01-01", null, null))).body()).path("id").asText();
        assertEquals(403, call("POST", API + "/entries/" + own + "/reviews", dual, review("INDEPENDENT", "REVIEWED_OK")).statusCode());
        assertEquals(403, call("POST", API + "/entries/" + own + "/reviews", dual, review("OWNER_INTERNAL", "REVIEWED_OK")).statusCode());
        assertEquals(403, call("POST", API + "/entries/" + own + "/reviews", owner, review("INDEPENDENT", "REVIEWED_OK")).statusCode());
        assertEquals(404, call("POST", API + "/entries/" + own + "/reviews", manager, review("INDEPENDENT", "REVIEWED_OK")).statusCode());
        assertEquals(422, call("POST", API + "/entries/" + own + "/reviews", reviewer, "{\"basis\":\"INDEPENDENT\",\"outcome\":\"REVIEWED_OK\",\"reason\":\"curto\"}").statusCode());
        assertEquals(404, call("POST", API + "/entries/" + UUID.randomUUID() + "/reviews", reviewer, review("INDEPENDENT", "REVIEWED_OK")).statusCode());
        ok(call("POST", API + "/entries/" + own + "/reviews", owner, review("OWNER_INTERNAL", "REVIEWED_OK")));
        var afterInternal = find(json.readTree(ok(call("GET", API, reviewer, null)).body()).path("entries"), own);
        assertEquals("REVIEWED_OK", afterInternal.path("internalReview").path("outcome").asText());
        assertTrue(afterInternal.path("independentReview").isNull());
        assertEquals("NOT_ADMITTED", afterInternal.path("admission").asText());
        ok(call("POST", API + "/entries/" + own + "/reviews", reviewer, review("INDEPENDENT", "REJECTED")));
        var both = find(json.readTree(ok(call("GET", API, owner, null)).body()).path("entries"), own);
        assertEquals("REVIEWED_OK", both.path("internalReview").path("outcome").asText());
        assertEquals("REJECTED", both.path("independentReview").path("outcome").asText());

        // Candidate boundaries (VM0038 v1.1, Table 1): CO2_ONLY resolves only a fossil-CO2 factor, never an aggregate.
        var presets = json.readTree(ok(call("GET", API + "/boundaries", manager, null)).body());
        assertEquals(2, presets.size());
        assertEquals("CO2_ONLY", presets.get(0).path("id").asText());
        assertEquals("CO2", presets.get(0).path("baselineGases").get(0).asText());
        assertEquals(1, presets.get(0).path("projectGases").size());
        assertTrue(presets.get(0).path("source").asText().contains("Tabela 1"));
        String res = API + "/boundary-resolution?fuelId=" + product + "&scope=TTW&boundary=";
        var found = json.readTree(ok(call("GET", res + "CO2_ONLY&date=2024-06-01", manager, null)).body());
        assertEquals("RESOLVED_CANDIDATE", found.path("state").asText());
        assertEquals("NOT_ADMITTED", found.path("admission").asText());
        var requirement = found.path("requirements").get(0);
        assertEquals("FOUND", requirement.path("state").asText());
        assertEquals("NOT_REVIEWED_OK", requirement.path("review").asText());
        assertEquals("kgCO2/L", requirement.path("unit").asText());
        assertEquals("tCO2/USgal", requirement.path("methodUnit").asText());
        assertEquals("0.011356235352", requirement.path("methodValue").asText());
        var missing = json.readTree(ok(call("GET", res + "CO2_ONLY&date=2023-06-01", manager, null)).body());
        assertEquals("UNRESOLVED", missing.path("state").asText());
        assertEquals("MISSING", missing.path("requirements").get(0).path("state").asText());
        // From 2025 only the aggregate (CO2e, AR5_100) exists: it does not satisfy a CO2-only boundary.
        var aggregateOnly = json.readTree(ok(call("GET", res + "CO2_ONLY&date=2025-06-01", manager, null)).body());
        assertEquals("UNRESOLVED", aggregateOnly.path("state").asText());
        assertTrue(aggregateOnly.path("requirements").get(0).path("note").asText().contains("CO2e agregado"));
        // The three-gas boundary: CO2 and CH4 exist but N2O does not; the aggregate is not mixed with separate factors.
        var mixed = json.readTree(ok(call("GET", res + "CO2_CH4_N2O&gwpSet=AR5_100&date=2024-06-01", manager, null)).body());
        assertEquals("UNRESOLVED", mixed.path("state").asText());
        assertEquals("MISSING", mixed.path("requirements").get(2).path("state").asText());
        assertTrue(mixed.path("requirements").get(2).path("note").asText().contains("dupla contagem"));
        // Without separate factors the aggregate with the declared GWP set covers the three gases.
        ok(call("POST", API + "/entries", manager, datum(comp, "EMISSION_FACTOR", "CO2E_AGGREGATE", "TTW", "AR5_100", "2.9", "kgCO2e/L", "2024-01-01", null, null)));
        var onlyAggregate = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=" + comp + "&scope=TTW&boundary=CO2_CH4_N2O&gwpSet=AR5_100&date=2025-06-01", manager, null)).body());
        assertEquals("FOUND_AGGREGATE", onlyAggregate.path("requirements").get(0).path("state").asText());
        assertEquals(1, onlyAggregate.path("requirements").size());
        assertEquals("tCO2e/USgal", onlyAggregate.path("requirements").get(0).path("methodUnit").asText());
        assertEquals("0.010977694", onlyAggregate.path("requirements").get(0).path("methodValue").asText().substring(0, 11));
        assertEquals(422, call("GET", res + "OUTRA&date=2024-06-01", manager, null).statusCode());
        assertEquals(422, call("GET", res + "CO2_ONLY&date=ontem", manager, null).statusCode());
        assertEquals(404, call("GET", res + "CO2_ONLY&date=2024-06-01", outsider, null).statusCode());
        assertEquals(404, call("GET", API + "/boundaries", outsider, null).statusCode());

        // Etapa 5: the Brazilian seed (docs/data/fuel-catalog-br-seed.json) is accepted by the API as published, nothing is
        // reviewed or admitted by registering it, and once reviewed the derived gasolina C factor resolves under CO2_ONLY.
        var seed = json.readTree(java.nio.file.Files.readString(java.nio.file.Path.of("../docs/data/fuel-catalog-br-seed.json")));
        var ids = new java.util.ArrayList<String>();
        for (var item : seed.path("entries")) {
            var body = (com.fasterxml.jackson.databind.node.ObjectNode) item.deepCopy();
            body.remove("evidenceUrl");
            for (var name : new java.util.ArrayList<String>() {{ body.fieldNames().forEachRemaining(this::add); }})
                if (body.get(name).isNull()) body.remove(name);
            var created = json.readTree(ok(call("POST", API + "/entries", manager, json.writeValueAsString(body))).body());
            assertEquals("NOT_ADMITTED", created.path("admission").asText());
            if (item.has("ipccValue")) { // 2981.1 vs 3069.99 gCO2/kg: -2.8951...%, same unit, never a validation
                assertEquals("COMPARED_SAME_UNIT", created.path("qaqc").path("state").asText());
                assertTrue(new java.math.BigDecimal(created.path("qaqc").path("relativeDeviation").asText()).doubleValue() < -0.028
                        && new java.math.BigDecimal(created.path("qaqc").path("relativeDeviation").asText()).doubleValue() > -0.030, created.toString());
            }
            ids.add(created.path("id").asText());
        }
        var before = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=GASOLINE_C&scope=TTW&boundary=CO2_ONLY&date=2026-09-01", manager, null)).body());
        assertEquals("NOT_REVIEWED_OK", before.path("requirements").get(0).path("review").asText());
        for (String id : ids) ok(call("POST", API + "/entries/" + id + "/reviews", owner, review("OWNER_INTERNAL", "REVIEWED_OK")));
        var after = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=GASOLINE_C&scope=TTW&boundary=CO2_ONLY&date=2026-09-01", manager, null)).body());
        assertEquals("RESOLVED_CANDIDATE", after.path("state").asText(), after.toString());
        assertEquals("INTERNAL_OK", after.path("requirements").get(0).path("review").asText());
        // 1504.143816 gCO2/L x 3.785411784 L/USgal / 1e6 g/t, exact.
        var expected = new java.math.BigDecimal("1504.143816").multiply(new java.math.BigDecimal("3.785411784")).divide(new java.math.BigDecimal("1000000"));
        assertEquals(0, expected.compareTo(new java.math.BigDecimal(after.path("requirements").get(0).path("methodValue").asText())), after.toString());
        assertEquals("tCO2/USgal", after.path("requirements").get(0).path("methodUnit").asText());
        // The E30 window (CNPE 9/2025) resolves its own factor, 1548.38334 gCO2/L, and does not borrow the E32 one.
        var e30 = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=GASOLINE_C&scope=TTW&boundary=CO2_ONLY&date=2026-01-15", manager, null)).body());
        assertEquals("RESOLVED_CANDIDATE", e30.path("state").asText(), e30.toString());
        assertEquals(0, new java.math.BigDecimal("1548.38334").multiply(new java.math.BigDecimal("3.785411784")).divide(new java.math.BigDecimal("1000000"))
                .compareTo(new java.math.BigDecimal(e30.path("requirements").get(0).path("methodValue").asText())), e30.toString());
        // Before E30 (CNPE 9/2025) there is no blend factor either: nothing is presumed.
        var early = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=GASOLINE_C&scope=TTW&boundary=CO2_ONLY&date=2025-07-01", manager, null)).body());
        assertEquals("UNRESOLVED", early.path("state").asText());
        // The 3-gas boundary stays unresolved: no CH4/N2O datum for gasolina C and no aggregate.
        var threeGas = json.readTree(ok(call("GET", API + "/boundary-resolution?fuelId=GASOLINE_C&scope=TTW&boundary=CO2_CH4_N2O&date=2026-09-01", manager, null)).body());
        assertEquals("UNRESOLVED", threeGas.path("state").asText());

        // Append-only.
        assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c, "UPDATE app_fuel_entry SET value_text='9' WHERE id=?::uuid", efId)));
        assertThrows(RuntimeException.class, () -> database.transaction(c -> update(c, "DELETE FROM app_fuel_entry_review WHERE entry_id=?::uuid", own)));
    }

    /** A one-time code cannot be replayed, so a session already verified by an earlier test is reused. */
    private void verify(String who) throws Exception {
        var current = call("GET", "/v1/compliance/session", who, null, null);
        if (current.statusCode() == 200 && json.readTree(current.body()).path("verified").asBoolean()) return;
        assertEquals(200, call("POST", "/v1/compliance/session", who, null, currentCode()).statusCode());
    }

    private static JsonNode find(JsonNode list, String id) {
        for (var item : list) if (item.path("id").asText().equals(id)) return item;
        throw new AssertionError("not listed: " + id);
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
                    + ";" + DUAL + ":" + secret + ":" + ORG + ";" + OWNER + ":" + secret + ":" + ORG + ";" + OUTSIDER + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            result.put("carbonflow.platform.fuel-catalog.managers", MANAGER + "," + DUAL);
            result.put("carbonflow.platform.fuel-catalog.reviewers", REVIEWER + "," + DUAL);
            result.put("carbonflow.platform.fuel-catalog.owners", OWNER.toString());
            return result;
        }
    }
}
