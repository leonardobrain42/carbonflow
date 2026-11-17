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
class ProjectDraftResourceTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;

    private final HttpClient client = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    @Test
    void persistsDraftAndReplaysConcurrentCreateAndVersionedPatch() throws Exception {
        Actor owner = actor();
        UUID organization = organization(owner.id());
        try {
            String createKey = UUID.randomUUID().toString();
            String input = "{\"organizationId\":\"" + organization + "\",\"title\":\"  Estação em Planaltina  \","
                    + "\"description\":\" Recarga elétrica \"}";
            var request = command("POST", "/v1/projects", owner.cookie(), createKey, null, input);
            CompletableFuture<HttpResponse<String>> a = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            CompletableFuture<HttpResponse<String>> b = client.sendAsync(request, HttpResponse.BodyHandlers.ofString());
            var firstCreated = a.join();
            var secondCreated = b.join();
            assertEquals(201, firstCreated.statusCode(), firstCreated.body());
            assertEquals(201, secondCreated.statusCode(), secondCreated.body());
            assertEquals(json.readTree(firstCreated.body()), json.readTree(secondCreated.body()));
            JsonNode result = json.readTree(firstCreated.body());
            String id = result.path("projectId").asText();
            assertEquals(id, UUID.fromString(id).toString());
            assertNotEquals("PIL-SYN-BR-001", id);
            assertEquals("SANDBOX", result.path("environment").asText());
            assertEquals("DRAFT", result.path("state").asText());
            assertEquals("Estação em Planaltina", result.path("title").asText());
            assertEquals("Recarga elétrica", result.path("description").asText());
            assertEquals(1, result.path("version").asInt());
            assertEquals("\"1\"", firstCreated.headers().firstValue("ETag").orElseThrow());
            assertEquals(1L, count("app_project", id));
            assertEquals(1L, count("app_project_command", id));
            assertEquals(1L, count("app_project_event", id));

            String path = "/v1/projects/" + id + "/metadata";
            var read = get(path, owner.cookie());
            assertEquals(200, read.statusCode());
            assertEquals("\"1\"", read.headers().firstValue("ETag").orElseThrow());
            assertEquals(result, json.readTree(read.body()));
            assertTrue(json.readTree(get("/v1/account-session", owner.cookie()).body()).path("projects")
                    .toString().contains(id));

            var missing = command("PATCH", path, owner.cookie(), UUID.randomUUID().toString(), null,
                    "{\"title\":\"Nova versão\"}");
            assertEquals(428, client.send(missing, HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(412, client.send(command("PATCH", path, owner.cookie(), UUID.randomUUID().toString(),
                    "\"0\"", "{\"title\":\"Nova versão\"}"), HttpResponse.BodyHandlers.ofString()).statusCode());

            String editKey = UUID.randomUUID().toString();
            var change = command("PATCH", path, owner.cookie(), editKey, "\"1\"",
                    "{\"title\":\"Novo título\",\"description\":null}");
            var changed = client.send(change, HttpResponse.BodyHandlers.ofString());
            var replay = client.send(change, HttpResponse.BodyHandlers.ofString());
            assertEquals(200, changed.statusCode());
            assertEquals(200, replay.statusCode());
            assertEquals(json.readTree(changed.body()), json.readTree(replay.body()));
            assertEquals("\"2\"", changed.headers().firstValue("ETag").orElseThrow());
            assertEquals("Novo título", json.readTree(changed.body()).path("title").asText());
            assertTrue(json.readTree(changed.body()).path("description").isNull());
            assertEquals(2, json.readTree(get(path, owner.cookie()).body()).path("version").asInt());
            assertEquals(2L, count("app_project_command", id));
            assertEquals(2L, count("app_project_event", id));

            var reused = client.send(command("PATCH", path, owner.cookie(), editKey, "\"1\"",
                    "{\"title\":\"Mudar indevidamente\"}"), HttpResponse.BodyHandlers.ofString());
            assertEquals(409, reused.statusCode());
            assertEquals("CHAVE_REUSADA", json.readTree(reused.body()).path("code").asText());
            assertEquals(412, client.send(command("PATCH", path, owner.cookie(), UUID.randomUUID().toString(),
                    "\"1\"", "{\"title\":\"Perdido\"}"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(409, client.send(command("POST", "/v1/projects", owner.cookie(), createKey, null,
                    "{\"organizationId\":\"" + organization + "\",\"title\":\"Outro\"}"),
                    HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(2, json.readTree(get(path, owner.cookie()).body()).path("version").asInt());
        } finally {
            logoutSeed(owner);
        }
    }

    @Test
    void keepsCreatorScopedAndRejectsForeignOrganizationRevokedMemberAndPilot() throws Exception {
        Actor owner = actor();
        Actor other = actor();
        UUID organization = organization(owner.id());
        membership(organization, other.id());
        try {
            String initial = "{\"organizationId\":\"" + organization + "\",\"title\":\"Rascunho reservado\"}";
            var created = post("/v1/projects", owner.cookie(), initial);
            assertEquals(201, created.statusCode());
            String projectId = json.readTree(created.body()).path("projectId").asText();
            String path = "/v1/projects/" + projectId + "/metadata";

            assertEquals(404, get(path, other.cookie()).statusCode());
            assertEquals(404, post("/v1/projects", other.cookie(),
                    "{\"organizationId\":\"" + UUID.randomUUID() + "\",\"title\":\"Fraude\"}").statusCode());
            assertEquals(401, get(path, null).statusCode());
            assertEquals(0, json.readTree(get("/v1/account-session", other.cookie()).body())
                    .path("projects").size());

            var invalid = client.send(command("POST", "/v1/projects", owner.cookie(), UUID.randomUUID().toString(),
                    null, "{\"organizationId\":\"" + organization + "\",\"title\":\" \"}"),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(400, invalid.statusCode());

            database.transaction(connection -> update(connection, """
                    UPDATE app_organization_membership SET revoked_at = statement_timestamp()
                    WHERE organization_id = ? AND identity_id = ?
                    """, organization, owner.id()));
            assertEquals(404, get(path, owner.cookie()).statusCode());
            assertEquals(404, client.send(command("PATCH", path, owner.cookie(), UUID.randomUUID().toString(),
                    "\"1\"", "{\"title\":\"Não pode\"}"), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(0, json.readTree(get("/v1/account-session", owner.cookie()).body())
                    .path("projects").size());
        } finally {
            logoutSeed(owner);
            logoutSeed(other);
        }
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(connection -> {
            update(connection, """
                    INSERT INTO app_identity (id, email_normalized, display_name, status, email_verified_at)
                    VALUES (?, ?, 'Conta de teste', 'ACTIVE', statement_timestamp())
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
            update(connection, "INSERT INTO app_organization (id, environment, display_name) VALUES (?, 'SANDBOX', 'Org')", id);
            update(connection, """
                    INSERT INTO app_organization_membership (organization_id, identity_id, role)
                    VALUES (?, ?, 'DRAFT_OWNER')
                    """, id, owner);
            return null;
        });
        return id;
    }

    private void membership(UUID organization, UUID member) {
        database.transaction(connection -> update(connection, """
                INSERT INTO app_organization_membership (organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER')
                """, organization, member));
    }

    private void logoutSeed(Actor actor) {
        database.transaction(connection -> update(connection, "DELETE FROM app_account_session WHERE identity_id = ?", actor.id()));
    }

    private long count(String table, String id) {
        String sql = switch (table) {
            case "app_project" -> "SELECT count(*) FROM app_project WHERE id = ?";
            case "app_project_command" -> "SELECT count(*) FROM app_project_command WHERE project_id = ?";
            case "app_project_event" -> "SELECT count(*) FROM app_project_event WHERE project_id = ?";
            default -> throw new IllegalArgumentException(table);
        };
        return database.read(connection -> first(connection, sql, row -> row.getLong(1), id));
    }

    private HttpResponse<String> post(String path, String cookie, String body) throws Exception {
        return client.send(command("POST", path, cookie, UUID.randomUUID().toString(), null, body),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (cookie != null) request.header("Cookie", cookie);
        return client.send(request.GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest command(String method, String path, String cookie, String key, String ifMatch, String body) {
        var builder = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path))
                .header("Content-Type", "application/json");
        if (cookie != null) builder.header("Cookie", cookie);
        if (key != null) builder.header("Idempotency-Key", key);
        if (ifMatch != null) builder.header("If-Match", ifMatch);
        return builder.method(method, HttpRequest.BodyPublishers.ofString(body)).build();
    }
}
