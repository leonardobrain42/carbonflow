package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.math.BigDecimal;
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

/** Fictional factors only. A pin is not an admitted E8 input and never defaults IR or Dy to 1. */
@QuarkusTest
@QuarkusTestResource(value = OpenProjectParameterBindingsIntegrationTest.Settings.class,
    restrictToAnnotatedClass = true)
class OpenProjectParameterBindingsIntegrationTest {
    static final UUID MANAGER = UUID.fromString("fa000000-0000-4000-8000-000000000001");
    static final UUID REVIEWER = UUID.fromString("fa000000-0000-4000-8000-000000000002");
    static final UUID OUTSIDER = UUID.fromString("fa000000-0000-4000-8000-000000000003");
    static final UUID OWNER = UUID.fromString("fa000000-0000-4000-8000-000000000010");
    static final UUID ORG = UUID.fromString("fa000000-0000-4000-8000-000000000004");
    static final UUID OTHER_ORG = UUID.fromString("fa000000-0000-4000-8000-000000000005");
    static final byte[] TOTP = "11223344556677889900".getBytes(StandardCharsets.US_ASCII);

    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void projectPinKeepsAbsenceZeroAndOneDistinctAndDoesNotAdmitE8() throws Exception {
        String manager = actor(MANAGER), reviewer = actor(REVIEWER), outsider = actor(OUTSIDER);
        String owner = actor(OWNER);
        seed();
        assertEquals(403, call("GET", "/v1/platform/open-parameter-bindings/options",
            manager, null, null, null).statusCode());
        verify(manager);
        verify(reviewer);
        String fuel = reviewedFactor("/v1/platform/open-fossil-factors", manager, reviewer,
            "fa000000-0000-4000-8000-000000000008");
        String grid = reviewedFactor("/v1/platform/open-grid-factors", manager, reviewer,
            "fa000000-0000-4000-8000-000000000018");
        String project = project(owner);
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                VALUES (?, ?, 'SANDBOX', 1, ?, 1, ?::jsonb)
                """, project, ORG, OWNER, "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            return null;
        });
        long pilotBefore = database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_run", r -> r.getLong(1)));
        String pins = "/v1/platform/open-parameter-bindings";
        assertEquals(404, call("POST", pins, reviewer, key(), "\"0\"",
            pin(project, "IR", null, "1")).statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            "{\"projectId\":\"" + project + "\",\"parameterKind\":\"IR\",\"vintageYear\":2036,"
                + "\"useDefault\":true}").statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            "{\"projectId\":\"" + project + "\",\"parameterKind\":\"DY\",\"vintageYear\":2036}").statusCode());
        assertEquals(422, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_FUEL", fuel, null)
                .replace("\"vintageYear\":2036", "\"vintageYear\":2035")).statusCode());
        assertEquals(404, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_GRID", fuel, null)).statusCode());

        String idempotency = key();
        JsonNode one = json(call("POST", pins, manager, idempotency, "\"0\"",
            pin(project, "IR", null, "1")), 201);
        assertEquals("DECLARED_EXPLICIT", one.path("valueKind").asText());
        assertTrue(one.path("explicitOne").asBoolean());
        assertEquals("1", one.path("declaredValue").asText());
        assertFalse(one.path("usableForE8").asBoolean());
        assertTrue(one.path("gates").toString().contains("EXPLICIT_ONE_IS_NOT_A_DEFAULT"));
        assertEquals(200, call("POST", pins, manager, idempotency, "\"0\"",
            pin(project, "IR", null, "1")).statusCode());
        assertEquals(412, call("POST", pins, manager, key(), "\"0\"",
            pin(project, "IR", null, "0.8")).statusCode());

        JsonNode zero = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "DY", null, "0")), 201);
        assertEquals("DECLARED_ZERO", zero.path("valueKind").asText());
        assertFalse(zero.path("explicitOne").asBoolean());
        assertEquals("0", zero.path("declaredValue").asText());

        JsonNode fuelPin = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_FUEL", fuel, null)), 201);
        assertEquals("REFERENCE", fuelPin.path("valueKind").asText());
        assertEquals("tCO2e/USgal", fuelPin.path("normalizedUnit").asText());
        assertTrue(fuelPin.path("declaredValue").isNull());
        assertFalse(fuelPin.path("usableForE8").asBoolean());
        JsonNode gridPin = json(call("POST", pins, manager, key(), "\"0\"",
            pin(project, "EF_GRID", grid, null)), 201);
        assertEquals("tCO2e/kWh", gridPin.path("normalizedUnit").asText());
        assertEquals("DIVIDE_BY_1000_KWH_PER_MWH", gridPin.path("conversionFormula").asText());

        String projectPath = "/v1/projects/" + project + "/technical/open-parameter-bindings";
        assertEquals(404, call("GET", projectPath, outsider, null, null, null).statusCode());
        String visible = call("GET", projectPath, owner, null, null, null).body();
        JsonNode listed = json(call("GET", projectPath, owner, null, null, null), 200);
        assertEquals(4, listed.size());
        assertFalse(visible.contains("0.007570823568"), visible);
        assertFalse(visible.contains("0.000400000000"), visible);
        assertTrue(visible.contains("\"declaredValue\":\"1\""));
        assertTrue(visible.contains("\"declaredValue\":\"0\""));
        assertTrue(listed.toString().contains("\"usableForE8\":false"));

        JsonNode readiness = json(call("GET",
            "/v1/projects/" + project + "/technical/open-calculation-readiness",
            owner, null, null, null), 200);
        assertFalse(readiness.path("canRunE8").asBoolean());
        assertFalse(readiness.path("canIssueVcu").asBoolean());
        assertEquals("NOT_ADMITTED", gate(readiness, "EF_FUEL"));
        assertEquals("NOT_ADMITTED", gate(readiness, "EF_GRID"));
        assertEquals("NOT_ADMITTED", gate(readiness, "IR"));
        assertEquals("NOT_ADMITTED", gate(readiness, "DY"));
        assertTrue(reason(readiness, "IR").contains("não é um default"));
        assertTrue(reason(readiness, "DY").contains("Zero declarado"));
        assertFalse(reason(readiness, "EF_FUEL").contains("0.00757"));
        assertTrue(readiness.path("emissions").path("erTco2e").isNull());

        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                VALUES (?, ?, 'SANDBOX', 2, ?, 1, ?::jsonb)
                """, project, ORG, OWNER, "{\"1\":{\"methodologyCandidate\":\"VM0038\"}}");
            return null;
        });
        JsonNode stale = json(call("GET", projectPath, owner, null, null, null), 200);
        assertTrue(stale.toString().contains("STALE_NOT_ADMITTED"));
        JsonNode after = json(call("GET",
            "/v1/projects/" + project + "/technical/open-calculation-readiness",
            owner, null, null, null), 200);
        assertEquals("STALE", gate(after, "IR"));
        assertFalse(after.path("canRunE8").asBoolean());
        long pilotAfter = database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_run", r -> r.getLong(1)));
        assertEquals(pilotBefore, pilotAfter);
        assertEquals(0L, database.read(c -> first(c, """
            SELECT count(*) FROM app_open_project_parameter_binding WHERE usable_for_e8
            """, r -> r.getLong(1))).longValue());
    }

    private String reviewedFactor(String root, String manager, String reviewer, String object)
            throws Exception {
        JsonNode created = json(call("POST", root, manager, null, null,
            "{\"sourceObjectId\":\"" + object + "\",\"rowIndex\":2}"), 201);
        assertFalse(created.path("usableForE8").asBoolean());
        String review = """
            {"outcome":"APPROVED_FOR_SCOPE",
             "ruleReference":"VM0038-1.1 internal factor check only",
             "evidenceLocator":"CSV registro 1; fictional source",
             "reason":"Unit, year and gas checked in this fictional test only"}
            """;
        JsonNode reviewed = json(call("POST", root + "/" + created.path("id").asText() + "/reviews",
            reviewer, null, null, review), 201);
        assertEquals("CHECKED_INTERNAL_NOT_ADMITTED", reviewed.path("status").asText());
        assertFalse(reviewed.path("usableForE8").asBoolean());
        return created.path("id").asText();
    }

    private String pin(String project, String kind, String candidate, String declared) {
        String value = declared == null ? "" : ",\"declaredValue\":\"" + declared
            + "\",\"ruleReference\":\"VM0038-1.1 explicit project rule\","
            + "\"evidenceLocator\":\"prova ficticia do parametro\"";
        String source = candidate == null ? "" : ",\"candidateId\":\"" + candidate + "\"";
        return "{\"projectId\":\"" + project + "\",\"parameterKind\":\"" + kind
            + "\",\"vintageYear\":2036" + source + value + "}";
    }

    private static String gate(JsonNode readiness, String code) {
        for (JsonNode gate : readiness.path("gates")) {
            if (code.equals(gate.path("code").asText())) return gate.path("state").asText();
        }
        return "";
    }

    private static String reason(JsonNode readiness, String code) {
        for (JsonNode gate : readiness.path("gates")) {
            if (code.equals(gate.path("code").asText())) return gate.path("reason").asText();
        }
        return "";
    }

    private void seed() {
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_organization(id, environment, display_name)
                VALUES (?,'SANDBOX','Organizacao ficticia do pino'),
                       (?,'SANDBOX','Outra organizacao ficticia')
                ON CONFLICT(id) DO NOTHING
                """, ORG, OTHER_ORG);
            update(c, """
                INSERT INTO app_organization_membership(organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER') ON CONFLICT DO NOTHING
                """, ORG, OWNER);
            source(c, "OPEN-FOSSIL-EF", "EF_FUEL_GASOLINE", "tCO2e/L", new BigDecimal("0.002"),
                "TEST-FOSSIL-PIN-2036",
                UUID.fromString("fa000000-0000-4000-8000-000000000006"),
                UUID.fromString("fa000000-0000-4000-8000-000000000007"),
                UUID.fromString("fa000000-0000-4000-8000-000000000008"),
                UUID.fromString("fa000000-0000-4000-8000-000000000009"),
                "BASELINE_BE");
            source(c, "OPEN-GRID-EF", "EF_GRID_CO2E", "tCO2e/MWh", new BigDecimal("0.400"),
                "TEST-GRID-PIN-2036",
                UUID.fromString("fa000000-0000-4000-8000-000000000016"),
                UUID.fromString("fa000000-0000-4000-8000-000000000017"),
                UUID.fromString("fa000000-0000-4000-8000-000000000018"),
                UUID.fromString("fa000000-0000-4000-8000-000000000019"),
                "PROJECT_EMISSIONS_PE");
            return null;
        });
    }

    private void source(java.sql.Connection c, String need, String metric, String unit,
                        BigDecimal value, String edition, UUID submission, UUID license,
                        UUID object, UUID assessment, String capability) throws java.sql.SQLException {
        String source = first(c, """
            SELECT source_id FROM app_methodology_source_need
            WHERE code = ? AND methodology = 'VM0038' AND methodology_edition = '1.1'
            """, r -> r.getString(1), need);
        update(c, """
            INSERT INTO app_source_version
             (source_id, edition, category, title, rights_holder, official_uri, origin)
            VALUES (?, ?, 'EXTERNAL_SOURCE', 'Tabela ficticia', 'Autor sintetico',
                    'https://example.org/factor', 'EXTERNAL')
            ON CONFLICT DO NOTHING
            """, source, edition);
        update(c, """
            INSERT INTO app_platform_source_submission
             (id, need_code, source_id, edition, category, title, rights_holder, publisher,
              official_uri, reference_year, locator, metadata_fingerprint, state,
              submitted_by, reviewed_by)
            VALUES (?, ?, ?, ?, 'EXTERNAL_SOURCE', 'Tabela ficticia', 'Autor sintetico',
                    'Editor sintetico', 'https://example.org/factor', 2036, 'CSV registro 1',
                    ?, 'METADATA_ACCEPTED', ?, ?)
            ON CONFLICT (id) DO NOTHING
            """, submission, need, source, edition, AccessSecrets.digest(edition), MANAGER, REVIEWER);
        update(c, """
            INSERT INTO app_content_license
             (id, source_id, edition, organization_id, submitted_by, environment, purpose,
              authorization_ref, proof_sha256, starts_at, ends_at)
            VALUES (?, ?, ?, ?, ?, 'SANDBOX', 'AUTHORIZED_PROCESSING', 'Somente teste',
                    ?, '2024-01-01', statement_timestamp() + interval '1 hour')
            ON CONFLICT (id) DO NOTHING
            """, license, source, edition, ORG, MANAGER, "d".repeat(64));
        update(c, """
            INSERT INTO app_content_license_decision
             (license_id, version, outcome, decided_by, reason)
            VALUES (?, 1, 'APPROVED', ?, 'Autorizacao sintetica independente')
            ON CONFLICT DO NOTHING
            """, license, REVIEWER);
        update(c, """
            INSERT INTO app_content_license_capability
             (license_id, decision_version, operation)
            VALUES (?, 1, 'STORE_ORIGINAL'), (?, 1, 'EXTRACT_TEXT'),
                   (?, 1, 'SHOW_EXCERPTS'), (?, 1, 'EXPORT_DERIVATIVES')
            ON CONFLICT DO NOTHING
            """, license, license, license, license);
        update(c, """
            INSERT INTO app_platform_source_object
             (id, submission_id, organization_id, uploaded_by, license_id, license_version,
              file_name, file_format, sha256, bytes_count, nonce, encrypted, state,
              parsed_rows, parser_version)
            VALUES (?, ?, ?, ?, ?, 1, 'fator.csv', 'CSV', ?, 16, ?, ?, 'APPROVED_FOR_SCOPE',
                    1, 'TABULAR-1')
            ON CONFLICT (id) DO NOTHING
            """, object, submission, ORG, MANAGER, license, AccessSecrets.digest(edition + "-sha"),
            new byte[12], new byte[32]);
        update(c, """
            INSERT INTO app_platform_source_candidate
             (object_id, row_index, record_key, metric, period_year, geography, unit,
              value_numeric, locator)
            VALUES (?, 2, ?, ?, 2036, 'BR', ?, ?, 'CSV registro 1')
            ON CONFLICT DO NOTHING
            """, object, metric, metric, unit, value);
        update(c, """
            INSERT INTO app_platform_source_assessment
             (id, object_id, outcome, methodology, methodology_edition, scenario,
              blocked_capability, metric, period_year, geography, unit, evidence_locator,
              reason, reviewed_by)
            VALUES (?, ?, 'APPROVED_FOR_SCOPE', 'VM0038', '1.1', 'OPEN_LDV_DCFC_SIN_BR',
                    ?, ?, 2036, 'BR', ?, 'CSV registro 1',
                    'Checagem interna ficticia da fonte', ?)
            ON CONFLICT DO NOTHING
            """, assessment, object, capability, metric, unit, REVIEWER);
    }

    private String project(String owner) throws Exception {
        JsonNode created = json(call("POST", "/v1/projects", owner, key(), null,
            "{\"organizationId\":\"" + ORG + "\",\"title\":\"Rede aberta para pino\"}"), 201);
        return created.path("projectId").asText();
    }

    private String actor(UUID id) {
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id, email_normalized, display_name, status, email_verified_at)
                VALUES (?, ?, 'Equipe ficticia', 'ACTIVE', statement_timestamp())
                ON CONFLICT (id) DO NOTHING
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash, identity_id, expires_at)
                VALUES (?, ?, statement_timestamp() + interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return token;
    }

    private void verify(String token) throws Exception {
        long counter = database.read(c -> first(c, """
            SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint
            """, r -> r.getLong(1)));
        assertEquals(200, call("POST", "/v1/compliance/session", token, null, null,
            "{\"code\":\"" + ComplianceStaff.codeAt(TOTP, counter) + "\"}").statusCode());
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private HttpResponse<String> call(String method, String path, String token, String idempotency,
                                       String match, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (token != null) builder.header("Cookie", AccountAccess.COOKIE + "=" + token);
        if (idempotency != null) builder.header("Idempotency-Key", idempotency);
        if (match != null) builder.header("If-Match", match);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody()
            : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response, int expected) throws Exception {
        assertEquals(expected, response.statusCode(), response.body());
        return mapper.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override public Map<String, String> start() {
            var config = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(TOTP);
            config.put("carbonflow.compliance.staff", MANAGER + ":" + secret + ":" + ORG
                + ";" + REVIEWER + ":" + secret + ":" + ORG
                + ";" + OUTSIDER + ":" + secret + ":" + OTHER_ORG);
            config.put("carbonflow.compliance.document-key",
                Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            config.put("carbonflow.platform.source-readiness.viewers",
                MANAGER + "," + REVIEWER + "," + OUTSIDER);
            config.put("carbonflow.platform.source-work.managers", MANAGER.toString());
            config.put("carbonflow.platform.source-technical.reviewers", REVIEWER.toString());
            return config;
        }
    }
}
