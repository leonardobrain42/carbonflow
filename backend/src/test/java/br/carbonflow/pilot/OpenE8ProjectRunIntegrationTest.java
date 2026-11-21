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

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AccountAccessTest.Inbox.class, restrictToAnnotatedClass = true)
class OpenE8ProjectRunIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client = HttpClient.newHttpClient();

    private record Actor(UUID id, String cookie) {}

    @Test
    void attemptIsBlockedReplayableAndLeavesThePilotUntouched() throws Exception {
        Actor owner = actor(), outsider = actor(), preparer = actor(), reviewer = actor();
        UUID org = organization(owner.id());
        String project = project(owner, org);
        String path = "/v1/projects/" + project + "/technical/open-monitoring/dossiers/M1/e8-runs";

        assertEquals(401, call("GET", path, null, null, null, null).statusCode());
        assertEquals(404, call("GET", path, outsider.cookie(), null, null, null).statusCode());
        assertEquals(404, call("POST", path, outsider.cookie(), key(), "\"0\"", "{}").statusCode());
        assertEquals(404, call("GET",
            "/v1/projects/PIL-SYN-BR-001/technical/open-monitoring/dossiers/M1/e8-runs",
            owner.cookie(), null, null, null).statusCode());
        assertEquals(428, call("POST", path, owner.cookie(), key(), null, "{}").statusCode());

        String idempotency = key();
        var created = call("POST", path, owner.cookie(), idempotency, "\"0\"", "{}");
        assertEquals(201, created.statusCode(), created.body());
        assertTrue(created.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode run = json(created);
        assertEquals("BLOCKED", run.path("state").asText());
        assertFalse(run.path("stale").asBoolean());
        assertFalse(run.path("canIssueVcu").asBoolean());
        assertTrue(run.path("memory").isNull());
        assertTrue(run.path("engine").isNull());
        assertEquals(1, run.path("version").asInt());
        assertEquals(7, run.path("equationPlan").size());
        assertEquals("NOT_CALCULATED", run.at("/equationPlan/6/status").asText());
        assertTrue(run.path("gates").toString().contains("IR"));
        assertTrue(run.path("gates").toString().contains("EF_FUEL"));
        assertTrue(run.path("gates").toString().contains("ED"));
        assertFalse(run.toString().contains("EF-SYN"));
        assertFalse(run.toString().contains("SIM-DY"));
        assertNull(run.path("beTco2e").textValue());
        String id = run.path("id").asText();
        String sha = run.path("inputSha256").asText();

        var replay = call("POST", path, owner.cookie(), idempotency, "\"0\"", "{}");
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(id, json(replay).path("id").asText());

        assertEquals(412, call("POST", path, owner.cookie(), key(), "\"0\"", "{}").statusCode());
        var sameSet = call("POST", path, owner.cookie(), key(), "\"1\"", "{}");
        assertEquals(200, sameSet.statusCode(), sameSet.body());
        assertEquals(id, json(sameSet).path("id").asText());
        assertEquals(sha, json(sameSet).path("inputSha256").asText());
        assertEquals(409, call("POST",
            "/v1/projects/" + project + "/technical/open-monitoring/dossiers/M2/e8-runs",
            owner.cookie(), idempotency, "\"0\"", "{}").statusCode());
        assertEquals(404, call("POST", path, owner.cookie(), key(), "\"1\"",
            "{\"energyBindingId\":\"" + UUID.randomUUID() + "\"}").statusCode());

        var listed = call("GET", path, owner.cookie(), null, null, null);
        assertEquals(200, listed.statusCode(), listed.body());
        assertTrue(json(listed).path("canRecord").asBoolean());
        assertEquals(id, json(listed).at("/runs/0/id").asText());
        assertEquals(200, call("GET", path + "/" + id, owner.cookie(), null, null, null).statusCode());

        String readiness = "/v1/projects/" + project + "/technical/open-calculation-readiness";
        var diagnosis = call("GET", readiness, owner.cookie(), null, null, null);
        assertEquals(200, diagnosis.statusCode(), diagnosis.body());
        assertFalse(json(diagnosis).path("canRunE8").asBoolean());
        assertFalse(json(diagnosis).path("canIssueVcu").asBoolean());
        assertNull(json(diagnosis).at("/emissions/erTco2e").textValue());
        assertFalse(json(diagnosis).has("memory"));

        UUID grant = grant(project, org, preparer.id(), owner.id(), "PREP");
        var prepared = call("POST", path, preparer.cookie(), key(), "\"1\"", "{}");
        assertEquals(200, prepared.statusCode(), prepared.body());
        assertEquals(id, json(prepared).path("id").asText());
        grant(project, org, reviewer.id(), owner.id(), "REV");
        assertEquals(200, call("GET", path, reviewer.cookie(), null, null, null).statusCode());
        assertFalse(json(call("GET", path, reviewer.cookie(), null, null, null)).path("canRecord").asBoolean());
        assertEquals(404, call("POST", path, reviewer.cookie(), key(), "\"1\"", "{}").statusCode());
        database.transaction(c -> {
            update(c, """
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(), revoked_by=? WHERE id=?
                """, owner.id(), grant);
            return null;
        });
        assertEquals(404, call("POST", path, preparer.cookie(), key(), "\"1\"", "{}").statusCode());
        assertEquals(0L, database.read(c -> first(c,
            "SELECT count(*) FROM pilot_selected_run WHERE project_id=?",
            r -> r.getLong(1), project)).longValue());
        assertEquals(0L, database.read(c -> first(c, """
            SELECT count(*) FROM app_open_e8_run WHERE can_issue_vcu
            """, r -> r.getLong(1))).longValue());
    }

    private UUID grant(String project, UUID org, UUID actor, UUID owner, String role) {
        UUID id = UUID.randomUUID();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, ?, ARRAY['CALCULATION']::text[],
                   statement_timestamp()+interval '3 days')
                """, id, project, org, actor, owner, role);
            return null;
        });
        return id;
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Run E8 teste','ACTIVE',statement_timestamp())
                """, id, id + "@example.invalid");
            update(c, """
                INSERT INTO app_account_session(token_hash,identity_id,expires_at)
                VALUES (?,?,statement_timestamp()+interval '1 hour')
                """, AccessSecrets.digest(token), id);
            return null;
        });
        return new Actor(id, AccountAccess.COOKIE + "=" + token);
    }

    private UUID organization(UUID owner) {
        UUID id = UUID.randomUUID();
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Run E8 teste')", id);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')", id, owner);
            return null;
        });
        return id;
    }

    private String project(Actor owner, UUID org) throws Exception {
        var result = call("POST", "/v1/projects", owner.cookie(), key(), null,
            "{\"organizationId\":\"" + org + "\",\"title\":\"Rede OPEN para run\"}");
        assertEquals(201, result.statusCode(), result.body());
        return json(result).path("projectId").asText();
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private HttpResponse<String> call(String method, String path, String cookie, String key,
            String ifMatch, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (cookie != null) builder.header("Cookie", cookie);
        if (key != null) builder.header("Idempotency-Key", key);
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
