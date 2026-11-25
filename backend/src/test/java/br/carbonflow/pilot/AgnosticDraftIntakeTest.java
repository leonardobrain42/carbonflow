package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AccountAccessTest.Inbox.class, restrictToAnnotatedClass = true)
class AgnosticDraftIntakeTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient http = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    private static final String[] EV_TERMS = {"carregador", "recarga", "DCFC", "EVR", "AFEC", "VT0011", "BEV",
        "VM0038-1.1", "vm0038-open", "OPEN_LDV", "installsChargers", "vehicleClass", "chargerType"};

    /** AGN-01 (D17): a non-electric draft with an unsupported candidate gets triage and gaps, no EV fields or fallback. */
    @Test
    void draftOfAnotherActivityGetsNoChargingFieldsOrFallback() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String root = "/v1/projects/" + project;
        assertEquals(200, call("PUT", root + "/triage/steps/1", owner.cookie(), UUID.randomUUID().toString(), "\"0\"",
            "{\"answers\":{\"objective\":\"Reduzir emissões de aterro\",\"activityType\":\"RESIDUOS\","
            + "\"methodologyCandidate\":\"ACM0001\"},\"nextStep\":2}").statusCode());
        assertEquals(200, call("PUT", root + "/triage/steps/2", owner.cookie(), UUID.randomUUID().toString(), "\"1\"",
            "{\"answers\":{\"activityDescription\":\"Captura de biogás em aterro sanitário\"},\"nextStep\":4}")
            .statusCode());

        JsonNode triage = json(call("GET", root + "/triage", owner.cookie(), null, null, null));
        assertEquals("GENERAL", triage.at("/journey/id").asText());
        assertEquals("SEM_JORNADA_CONFIGURADA", triage.at("/guidance/state").asText());
        assertEquals("UNSUPPORTED_MVP", triage.at("/diagnosis/classification").asText());
        String pending = triage.path("pending").toString() + triage.path("stages").toString();
        assertFalse(pending.contains("installsChargers"), pending);
        assertEquals("scopeNotes", triage.at("/stages/2/missingFields/0").asText());
        for (var q : triage.at("/journey/questions")) {
            for (String term : EV_TERMS) assertFalse(q.toString().contains(term), q.toString());
        }
        // The charging field is rejected for a draft that is not VM0038.
        assertEquals(400, call("PUT", root + "/triage/steps/2", owner.cookie(), UUID.randomUUID().toString(),
            "\"" + triage.path("version").asInt() + "\"", "{\"answers\":{\"installsChargers\":\"YES\"},\"nextStep\":4}")
            .statusCode());

        JsonNode readiness = json(call("GET", root + "/technical/open-calculation-readiness", owner.cookie(), null, null, null));
        assertEquals("ACM0001", readiness.path("methodology").asText());
        assertTrue(readiness.path("engine").isNull(), readiness.toString());
        assertTrue(readiness.path("scenario").isNull(), readiness.toString());
        assertFalse(readiness.path("canRunE8").asBoolean());
        assertFalse(readiness.path("canIssueVcu").asBoolean());
        assertEquals(1, readiness.path("gates").size());
        assertEquals("METHODOLOGY", readiness.at("/gates/0/code").asText());
        assertEquals("OUT_OF_SCOPE", readiness.at("/gates/0/state").asText());
        assertEquals(0, readiness.path("equationPlan").size());
        assertTrue(readiness.at("/emissions/erTco2e").isNull());
        for (String term : EV_TERMS) assertFalse(readiness.toString().contains(term), term + " " + readiness);

        JsonNode requirements = json(call("GET", root + "/technical/requirements", owner.cookie(), null, null, null));
        assertEquals("OUT_OF_SCOPE", requirements.at("/requirements/0/state").asText());
        assertFalse(requirements.path("canRunE8").asBoolean());
    }

    /** No candidate at all is a gap too, not a rejection and not a default to VM0038. */
    @Test
    void draftWithoutCandidateKeepsAMissingMethodologyGap() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        JsonNode readiness = json(call("GET", "/v1/projects/" + project + "/technical/open-calculation-readiness",
            owner.cookie(), null, null, null));
        assertEquals("METHODOLOGY", readiness.at("/gates/0/code").asText());
        assertEquals("MISSING", readiness.at("/gates/0/state").asText());
        assertEquals(1, readiness.path("gates").size());
        assertFalse(readiness.path("canRunE8").asBoolean());
        for (String term : EV_TERMS) assertFalse(readiness.toString().contains(term), term);
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(connection -> {
            update(connection, """
                    INSERT INTO app_identity (id, email_normalized, display_name, status, email_verified_at)
                    VALUES (?, ?, 'Triagem teste', 'ACTIVE', statement_timestamp())
                    """, id, id + "@example.invalid");
            update(connection, """
                    INSERT INTO app_account_session (token_hash, identity_id, expires_at)
                    VALUES (?, ?, statement_timestamp() + interval '1 hour')
                    """, AccessSecrets.digest(token), id);
            return null;
        });
        return new Actor(id, AccountAccess.COOKIE + "=" + token);
    }

    private UUID organization(UUID owner) {
        UUID id = UUID.randomUUID();
        database.transaction(connection -> {
            update(connection, """
                    INSERT INTO app_organization(id, environment, display_name)
                    VALUES (?, 'SANDBOX', 'Organização teste')
                    """, id);
            update(connection, """
                    INSERT INTO app_organization_membership(organization_id, identity_id, role)
                    VALUES (?, ?, 'DRAFT_OWNER')
                    """, id, owner);
            return null;
        });
        return id;
    }

    private void membership(UUID org, UUID actor) {
        database.transaction(connection -> update(connection, """
                INSERT INTO app_organization_membership (organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER')
                """, org, actor));
    }

    private String project(Actor owner, UUID org) throws Exception {
        var created = call("POST", "/v1/projects", owner.cookie(), UUID.randomUUID().toString(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto de triagem\"}");
        assertEquals(201, created.statusCode(), created.body());
        return json(created).path("projectId").asText();
    }

    /** Writes an older-screen revision (before D16), merging the given steps into the latest answers. */
    private void legacy(String project, int version, String steps) {
        database.transaction(connection -> update(connection, """
                INSERT INTO app_project_triage_revision
                  (project_id, organization_id, environment, version, actor_id, current_step, answers)
                SELECT project_id, organization_id, environment, ?, actor_id, 5, answers || ?::jsonb
                FROM app_project_triage_revision WHERE project_id = ? ORDER BY version DESC LIMIT 1
                """, version, steps, project));
    }

    private HttpResponse<String> call(String method, String path, String cookie,
                                      String key, String etag, String payload) throws Exception {
        return http.send(request(method, path, cookie, key, etag, payload),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest request(String method, String path, String cookie,
                                String key, String etag, String payload) {
        var request = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (cookie != null) request.header("Cookie", cookie);
        if (key != null) request.header("Idempotency-Key", key);
        if (etag != null) request.header("If-Match", etag);
        if (payload != null) request.header("Content-Type", "application/json");
        return request.method(method, payload == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(payload)).build();
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
