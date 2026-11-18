package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AccountAccessTest.Inbox.class, restrictToAnnotatedClass = true)
class ProjectTriageWizardTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    private final HttpClient http = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    @Test
    void fiveStepNavigationPersistsVersionsAndRecoversUnknownFields() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String endpoint = "/v1/projects/" + project + "/triage";
        var initial = call("GET", endpoint, owner.cookie(), null, null, null);
        assertEquals(200, initial.statusCode());
        assertEquals("\"0\"", initial.headers().firstValue("ETag").orElseThrow());
        assertEquals(1, json(initial).path("currentStep").asInt());
        assertEquals(0, json(initial).path("version").asInt());
        assertTrue(json(initial).path("pending").size() > 0);

        String key = UUID.randomUUID().toString();
        String payload = """
                {"answers":{"objective":" Avaliar nova frota ","activityType":"MOBILIDADE_ELETRICA",
                 "methodologyCandidate":"VM0038"},"nextStep":2}
                """;
        var request = request("PUT", endpoint + "/steps/1", owner.cookie(), key, "\"0\"", payload);
        var concurrentA = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var concurrentB = http.sendAsync(request, HttpResponse.BodyHandlers.ofString());
        var saved = concurrentA.join();
        var replay = concurrentB.join();
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(json(saved), json(replay));
        assertEquals(1, json(saved).path("version").asInt());
        assertEquals(2, json(saved).path("currentStep").asInt());
        assertEquals("Avaliar nova frota", json(saved).at("/answers/1/objective").asText());
        assertEquals("PREENCHIDO", json(saved).at("/stages/0/status").asText());
        assertEquals(1L, count(project));
        assertEquals(409, call("PUT", endpoint + "/steps/1", owner.cookie(), key, "\"0\"",
                "{\"answers\":{\"objective\":\"Outro\"},\"nextStep\":2}").statusCode());
        assertEquals(412, call("PUT", endpoint + "/steps/1", owner.cookie(), UUID.randomUUID().toString(),
                "\"0\"", payload).statusCode());
        assertEquals(428, call("PUT", endpoint + "/steps/1", owner.cookie(),
                UUID.randomUUID().toString(), null, payload).statusCode());
        assertEquals(400, call("PUT", endpoint + "/steps/8", owner.cookie(),
                UUID.randomUUID().toString(), "\"1\"", payload).statusCode());

        String partial = """
                {"answers":{"activityDescription":"Estudo técnico de recarga",
                 "baselinePractice":null},"nextStep":4}
                """;
        var second = call("PUT", endpoint + "/steps/2", owner.cookie(),
                UUID.randomUUID().toString(), "\"1\"", partial);
        assertEquals(200, second.statusCode(), second.body());
        assertEquals("PENDENTE", json(second).at("/stages/1/status").asText());
        assertEquals("NAO_AVALIADO", json(second).path("analysisState").asText());
        assertEquals(4, json(second).path("currentStep").asInt());
        assertTrue(json(second).path("pending").toString().contains("baselinePractice"));

        var moved = call("PATCH", endpoint + "/cursor", owner.cookie(),
                UUID.randomUUID().toString(), "\"2\"", "{\"nextStep\":1}");
        assertEquals(200, moved.statusCode(), moved.body());
        assertEquals(3, json(moved).path("version").asInt());
        assertEquals(1, json(moved).path("currentStep").asInt());
        var restored = call("GET", endpoint, owner.cookie(), null, null, null);
        assertEquals(200, restored.statusCode());
        assertEquals("\"3\"", restored.headers().firstValue("ETag").orElseThrow());
        assertEquals("Avaliar nova frota", json(restored).at("/answers/1/objective").asText());
        assertEquals("Estudo técnico de recarga", json(restored).at("/answers/2/activityDescription").asText());
        assertEquals(3L, count(project));

        // The wizard does not silently modify the E8 technical intake or the project metadata version.
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM app_project_intake_draft_revision WHERE project_id=?",
                row -> row.getLong(1), project)).longValue());
        var metadata = call("GET", "/v1/projects/" + project + "/metadata", owner.cookie(), null, null, null);
        assertEquals(1, json(metadata).path("version").asInt());
        assertEquals("DRAFT", json(metadata).path("state").asText());
    }

    @Test
    void syntheticTwentyFiveBevExampleIsSelfContainedAndCannotBeConfusedWithEmissions() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String endpoint = "/v1/projects/" + project + "/triage";
        String key = UUID.randomUUID().toString();
        var command = request("POST", endpoint + "/examples/bev-df-2027",
                owner.cookie(), key, "\"0\"", null);
        var first = http.sendAsync(command, HttpResponse.BodyHandlers.ofString());
        var retry = http.sendAsync(command, HttpResponse.BodyHandlers.ofString());
        var a = first.join();
        var b = retry.join();
        assertEquals(200, a.statusCode(), a.body());
        assertEquals(200, b.statusCode(), b.body());
        assertEquals(json(a), json(b));
        JsonNode result = json(a);
        assertEquals(1, result.path("version").asInt());
        assertEquals(5, result.path("currentStep").asInt());
        assertEquals(25, result.at("/answers/3/vehicleCount").asInt());
        assertEquals("BEV", result.at("/answers/3/propulsion").asText());
        assertEquals("DCFC", result.at("/answers/3/chargerType").asText());
        assertEquals("Brasília", result.at("/answers/4/locality").asText());
        assertEquals("DF", result.at("/answers/4/state").asText());
        assertEquals("2027-09-27", result.at("/answers/4/referenceDate").asText());
        assertEquals("25_BEV_BRASILIA_DF_2027_09_27", result.path("exampleKind").asText());
        assertEquals("NAO_AVALIADO", result.path("analysisState").asText());
        assertEquals("PENDENTE", result.at("/stages/3/status").asText());
        assertTrue(result.path("pending").toString().contains("evidenceStatus"));
        assertFalse(result.toString().contains("ER_CO2E"));
        assertFalse(result.toString().contains("VCU_EMITIDA"));
        assertEquals(1L, count(project));
        assertEquals(412, call("POST", endpoint + "/examples/bev-df-2027", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"", null).statusCode());
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM pilot_intake_revision WHERE project_id=?",
                row -> row.getLong(1), project)).longValue());
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM pilot_document_dossier WHERE project_id=?",
                row -> row.getLong(1), project)).longValue());
    }

    @Test
    void crossOrgSameOrgAndRevokedDraftOwnersReceiveOpaqueDenials() throws Exception {
        Actor owner = actor();
        Actor coworker = actor();
        Actor outsider = actor();
        UUID org = organization(owner.id());
        membership(org, coworker.id());
        organization(outsider.id());
        String project = project(owner, org);
        String endpoint = "/v1/projects/" + project + "/triage";

        assertEquals(404, call("GET", endpoint, coworker.cookie(), null, null, null).statusCode());
        assertEquals(404, call("GET", endpoint, outsider.cookie(), null, null, null).statusCode());
        assertEquals(404, call("PUT", endpoint + "/steps/1", outsider.cookie(), UUID.randomUUID().toString(),
                "\"0\"", "{\"answers\":{\"objective\":\"Invasão\"},\"nextStep\":2}").statusCode());
        assertEquals(401, call("GET", endpoint, null, null, null, null).statusCode());
        assertEquals(404, call("GET", "/v1/projects/PIL-SYN-BR-001/triage",
                owner.cookie(), null, null, null).statusCode());

        long audit = database.read(connection -> first(connection, """
                SELECT count(*) FROM app_project_technical_access_event
                WHERE actor_id = ? AND target_digest = ? AND outcome = 'DENIED'
                  AND action IN ('VIEW_TRIAGE', 'SAVE_TRIAGE')
                """, row -> row.getLong(1), outsider.id(), AccessSecrets.digest(project)));
        assertTrue(audit >= 2);

        database.transaction(connection -> update(connection, """
                UPDATE app_organization_membership SET revoked_at = statement_timestamp()
                WHERE organization_id = ? AND identity_id = ?
                """, org, owner.id()));
        assertEquals(404, call("GET", endpoint, owner.cookie(), null, null, null).statusCode());
        assertEquals(404, call("POST", endpoint + "/examples/bev-df-2027",
                owner.cookie(), UUID.randomUUID().toString(), "\"0\"", null).statusCode());
    }

    @Test
    void unconfiguredJourneyCollectsScopeAndAcceptsPreviousJustification() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String endpoint = "/v1/projects/" + project + "/triage";
        var plan = call("PUT", endpoint + "/steps/1", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"",
                "{\"answers\":{\"objective\":\"Gestão de resíduos\",\"activityType\":\"RESIDUOS\"},\"nextStep\":3}");
        assertEquals(200, plan.statusCode(), plan.body());
        var missingReason = call("PUT", endpoint + "/steps/3", owner.cookie(),
                UUID.randomUUID().toString(), "\"1\"",
                "{\"answers\":{},\"nextStep\":3}");
        assertEquals(200, missingReason.statusCode(), missingReason.body());
        assertTrue(json(missingReason).path("pending").toString().contains("scopeNotes"));
        assertEquals("GENERAL", json(missingReason).at("/journey/id").asText());
        assertEquals("Escopo e lacunas", json(missingReason).at("/stages/2/title").asText());
        var foreignAnswer = call("PUT", endpoint + "/steps/3", owner.cookie(),
                UUID.randomUUID().toString(), "\"2\"", "{\"answers\":{\"chargerType\":\"AC\"},\"nextStep\":4}");
        assertEquals(400, foreignAnswer.statusCode(), foreignAnswer.body());
        assertNotEquals("NAO_APLICAVEL_JUSTIFICADO",
                json(missingReason).at("/stages/2/status").asText());

        var justified = call("PUT", endpoint + "/steps/3", owner.cookie(),
                UUID.randomUUID().toString(), "\"2\"",
                "{\"answers\":{\"notApplicableReason\":\"Projeto não envolve veículos\"},\"nextStep\":4}");
        assertEquals(200, justified.statusCode(), justified.body());
        assertEquals("NAO_APLICAVEL_JUSTIFICADO", json(justified).at("/stages/2/status").asText());
        assertEquals("NAO_AVALIADO", json(justified).path("analysisState").asText());
    }

    @Test
    void vm0038SeparatesPilotExclusionsFromMethodConditionsAndRejectsUnknownCodes() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String endpoint = "/v1/projects/" + project + "/triage";
        var plan = call("PUT", endpoint + "/steps/1", owner.cookie(), UUID.randomUUID().toString(), "\"0\"", """
                {"answers":{"activityType":"MOBILIDADE_ELETRICA","methodologyCandidate":"VM0038 v1.1"},"nextStep":3}
                """);
        assertEquals(200, plan.statusCode(), plan.body());
        assertEquals("VM0038_V1_1", json(plan).at("/journey/id").asText());
        var outsidePilot = call("PUT", endpoint + "/steps/3", owner.cookie(), UUID.randomUUID().toString(),
                "\"1\"", """
                {"answers":{"vehicleClass":"LDV","propulsion":"PHEV","chargerType":"AC",
                            "electricitySource":"ON_SITE","associatedInfrastructure":"YES"},"nextStep":5}
                """);
        assertEquals(200, outsidePilot.statusCode(), outsidePilot.body());
        assertEquals(0, json(outsidePilot).at("/guidance/outsideMethodology").size());
        assertEquals(4, json(outsidePilot).at("/guidance/outsidePilot").size());
        assertEquals("NAO_AVALIADO", json(outsidePilot).path("analysisState").asText());
        var invalid = call("PUT", endpoint + "/steps/3", owner.cookie(), UUID.randomUUID().toString(),
                "\"2\"", """
                {"answers":{"propulsion":"Código inventado"},"nextStep":5}
                """);
        assertEquals(400, invalid.statusCode(), invalid.body());
        var incompatible = call("PUT", endpoint + "/steps/3", owner.cookie(), UUID.randomUUID().toString(),
                "\"2\"", """
                {"answers":{"vehicleClass":"LDV","propulsion":"PHEV","chargerType":"DCFC"},"nextStep":5}
                """);
        assertEquals(200, incompatible.statusCode(), incompatible.body());
        assertEquals(1, json(incompatible).at("/guidance/outsideMethodology").size());
        assertEquals("DCFC", json(incompatible).at("/answers/3/chargerType").asText());
        var technical = call("GET", "/v1/projects/" + project + "/technical/intake", owner.cookie(), null, null, null);
        assertEquals(200, technical.statusCode(), technical.body());
        assertEquals("VM0038_V1_1", json(technical).at("/journey/id").asText());
        assertTrue(json(technical).path("data").isNull());
        var notes = call("PUT", "/v1/projects/" + project + "/technical/intake", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"", """
                {"candidateMethodology":"VM0038 v1.1", "notes":"Levantar os registros de medição."}
                """);
        assertEquals(200, notes.statusCode(), notes.body());
        assertTrue(json(notes).at("/data/activity").isNull());
    }

    @Test
    void rejectsInvalidDatesCountsRightJustificationAndUndeclaredFields() throws Exception {
        Actor owner = actor();
        String project = project(owner, organization(owner.id()));
        String endpoint = "/v1/projects/" + project + "/triage";
        for (String input : new String[] {
                "{\"answers\":{\"vehicleCount\":-1},\"nextStep\":4}",
                "{\"answers\":{\"vehicleCount\":\"25\"},\"nextStep\":4}",
                "{\"answers\":{\"nonexistent\":\"teste\"},\"nextStep\":4}"
        }) assertEquals(400, call("PUT", endpoint + "/steps/3", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"", input).statusCode());
        assertEquals(400, call("PUT", endpoint + "/steps/4", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"",
                "{\"answers\":{\"referenceDate\":\"2027-02-30\"},\"nextStep\":5}").statusCode());
        assertEquals(400, call("PUT", endpoint + "/steps/4", owner.cookie(),
                UUID.randomUUID().toString(), "\"0\"",
                "{\"answers\":{\"rightsStatus\":\"NAO_APLICAVEL_JUSTIFICADO\"},\"nextStep\":5}").statusCode());
        assertEquals(0L, count(project));
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

    private long count(String project) {
        return database.read(connection -> first(connection,
                "SELECT count(*) FROM app_project_triage_revision WHERE project_id=?",
                row -> row.getLong(1), project));
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
