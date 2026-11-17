package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.QuarkusTestResourceLifecycleManager;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.eclipse.microprofile.config.ConfigProvider;
import org.junit.jupiter.api.Test;

@QuarkusTest
@QuarkusTestResource(value = AccountAccessTest.Inbox.class, restrictToAnnotatedClass = true)
class AccountAccessTest {
    private static final String ACCOUNTS = "/v1/accounts";
    private static final String SESSIONS = "/v1/account-session";
    private static final String PASSWORD = "Senha de ensaio 003";
    private static final String NEW_PASSWORD = "Senha recuperada 003";

    @TestHTTPResource
    URL base;

    @Inject
    ObjectMapper json;

    @Inject
    PilotDatabase database;

    @Test
    void confirmsEmailAndOpensAnEmptyWorkspaceInItsOwnSession() throws Exception {
        String email = email();
        String key = key();
        try (var client = HttpClient.newHttpClient()) {
            Map<String, String> data = Map.of("email", " " + email.toUpperCase(java.util.Locale.ROOT) + " ",
                    "name", " Conta de teste ");
            assertEquals(202, post(client, ACCOUNTS, data, key).statusCode());
            assertEquals(202, post(client, ACCOUNTS, data, key).statusCode());
            String oldToken = messages(email, "VERIFY_EMAIL").getFirst();
            assertEquals(1, messages(email, "VERIFY_EMAIL").size());
            assertEquals(401, login(client, email, PASSWORD).statusCode());

            var address = Map.of("email", email);
            String resend = key();
            assertEquals(202, post(client, ACCOUNTS + "/email-verification", address, resend).statusCode());
            assertEquals(202, post(client, ACCOUNTS + "/email-verification", address, resend).statusCode());
            var messages = messages(email, "VERIFY_EMAIL");
            assertEquals(2, messages.size());
            String token = messages.stream().filter(value -> !value.equals(oldToken)).findFirst().orElseThrow();
            assertEquals(422, confirm(client, oldToken, key()).statusCode());
            String confirmation = key();
            assertEquals(204, confirm(client, token, confirmation).statusCode());
            assertEquals(204, confirm(client, token, confirmation).statusCode());
            assertEquals(422, confirm(client, token, key()).statusCode());

            var login = login(client, email, PASSWORD);
            assertEquals(204, login.statusCode());
            String header = login.headers().firstValue("Set-Cookie").orElseThrow();
            assertTrue(header.startsWith("carbonflow_account_session="));
            assertTrue(header.contains("HttpOnly"));
            assertTrue(header.contains("SameSite=Strict"));
            String cookie = header.split(";", 2)[0];
            var current = get(client, SESSIONS, cookie);
            assertEquals(200, current.statusCode());
            assertEquals("no-store", current.headers().firstValue("Cache-Control").orElseThrow());
            var session = json.readTree(current.body());
            assertEquals(email, session.at("/identity/email").asText());
            assertEquals("Conta de teste", session.at("/identity/name").asText());
            assertDoesNotThrow(() -> UUID.fromString(session.at("/identity/id").asText()));
            assertEquals(json.readTree("[]"), session.path("projects"));
            assertFalse(session.has("roles"));
            assertEquals(401, get(client, "/v1/projects", cookie).statusCode());
            assertEquals(401, get(client, "/v1/session", cookie).statusCode());

            var pilot = post(client, "/v1/session", Map.of("identity", "SIM-PREP", "password", "piloto-prep"), null);
            assertEquals(204, pilot.statusCode());
            assertEquals(401, get(client, SESSIONS, cookie(pilot)).statusCode());
            var pilotSession = get(client, "/v1/session", cookie(pilot));
            assertEquals("SIM-PREP", json.readTree(pilotSession.body()).at("/identity/id").asText());
            assertEquals(204, delete(client, "/v1/session", cookie(pilot)).statusCode());

            var forged = client.send(postRequest(SESSIONS, Map.of("email", email, "password", PASSWORD), null)
                    .header("Origin", "https://other.example.invalid").build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, forged.statusCode());
            assertEquals("ORIGEM_NEGADA", json.readTree(forged.body()).path("code").asText());
            assertEquals(204, delete(client, SESSIONS, cookie).statusCode());
            assertEquals(401, get(client, SESSIONS, cookie).statusCode());
            assertEquals(204, delete(client, SESSIONS, cookie).statusCode());
        }
    }

    @Test
    void serializesSessionCapacityAndRecoversCredentialsWithoutKeepingOldSessions() throws Exception {
        String email = email();
        try (var client = HttpClient.newHttpClient()) {
            assertEquals(202, post(client, ACCOUNTS, Map.of("email", email, "name", "Recuperação"), key()).statusCode());
            assertEquals(204, confirm(client, messages(email, "VERIFY_EMAIL").getFirst(), key()).statusCode());
            var logins = List.of(logInAsync(client, email), logInAsync(client, email), logInAsync(client, email)).stream()
                    .map(CompletableFuture::join).toList();
            assertEquals(List.of(204, 204, 409), logins.stream().map(HttpResponse::statusCode).sorted().toList());
            var sessions = logins.stream().filter(result -> result.statusCode() == 204).map(AccountAccessTest::cookie).toList();
            String recovery = ACCOUNTS + "/password-recovery";
            String resetKey = key();
            assertEquals(202, post(client, recovery, Map.of("email", email()), key()).statusCode());
            assertEquals(202, post(client, recovery, Map.of("email", email), resetKey).statusCode());
            assertEquals(202, post(client, recovery, Map.of("email", email), resetKey).statusCode());
            var issued = messages(email, "RESET_PASSWORD");
            assertEquals(1, issued.size());
            String expired = issued.getFirst();
            database.transaction(connection -> update(connection, """
                    UPDATE app_account_challenge SET expires_at = statement_timestamp() - interval '1 second'
                    WHERE token_hash = ?
                    """, AccessSecrets.digest(expired)));

            String reset = ACCOUNTS + "/password-resets";
            assertEquals(422, post(client, reset, Map.of("token", expired, "password", NEW_PASSWORD), key()).statusCode());
            assertEquals(202, post(client, recovery, Map.of("email", email), key()).statusCode());
            String token = messages(email, "RESET_PASSWORD").stream().filter(value -> !value.equals(expired))
                    .findFirst().orElseThrow();
            var command = Map.of("token", token, "password", NEW_PASSWORD);
            String applyReset = key();
            assertEquals(204, post(client, reset, command, applyReset).statusCode());
            assertEquals(204, post(client, reset, command, applyReset).statusCode());
            assertEquals(409, post(client, reset, Map.of("token", token, "password", PASSWORD), applyReset).statusCode());
            assertEquals(422, post(client, reset, command, key()).statusCode());
            for (String cookie : sessions) {
                assertEquals(401, get(client, SESSIONS, cookie).statusCode());
            }
            assertEquals(401, login(client, email, PASSWORD).statusCode());
            var replacement = login(client, email, NEW_PASSWORD);
            assertEquals(204, replacement.statusCode());
            assertEquals(204, delete(client, SESSIONS, cookie(replacement)).statusCode());
            assertEquals(1L, database.read(connection -> first(connection, """
                    SELECT count(*) FROM app_account_event e JOIN app_identity i ON i.id = e.identity_id
                    WHERE i.email_normalized = ? AND e.action = 'PASSWORD_RESET'
                    """, row -> row.getLong(1), email)).longValue());
        }
    }

    @Test
    void throttlesRepeatedLoginBeforeCheckingTheCredential() throws Exception {
        String email = email();
        try (var client = HttpClient.newHttpClient()) {
            for (int attempt = 0; attempt < 8; attempt++) {
                assertEquals(401, login(client, email, PASSWORD).statusCode());
            }
            var response = login(client, email, PASSWORD);
            assertEquals(429, response.statusCode());
            assertEquals("LIMITE_DE_TENTATIVAS", json.readTree(response.body()).path("code").asText());
        }
    }

    private CompletableFuture<HttpResponse<String>> logInAsync(HttpClient client, String email) throws IOException {
        return client.sendAsync(postRequest(SESSIONS, Map.of("email", email, "password", PASSWORD), null).build(),
                HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> login(HttpClient client, String email, String password) throws Exception {
        return post(client, SESSIONS, Map.of("email", email, "password", password), null);
    }

    private HttpResponse<String> confirm(HttpClient client, String token, String key) throws Exception {
        return post(client, ACCOUNTS + "/email-confirmations", Map.of("token", token, "password", PASSWORD), key);
    }

    private HttpResponse<String> post(HttpClient client, String path, Map<String, String> data, String key) throws Exception {
        return client.send(postRequest(path, data, key).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder postRequest(String path, Map<String, String> data, String key) throws IOException {
        var builder = request(path).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(json.writeValueAsBytes(data)));
        return key == null ? builder : builder.header("Idempotency-Key", key);
    }

    private HttpResponse<String> get(HttpClient client, String path, String cookie) throws Exception {
        return client.send(request(path).header("Cookie", cookie).GET().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> delete(HttpClient client, String path, String cookie) throws Exception {
        return client.send(request(path).header("Cookie", cookie).DELETE().build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
    }

    private List<String> messages(String email, String purpose) throws IOException {
        var tokens = new ArrayList<String>();
        String inbox = ConfigProvider.getConfig().getValue("carbonflow.accounts.sandbox-inbox", String.class);
        try (var paths = Files.list(Path.of(inbox))) {
            for (Path path : paths.filter(p -> p.toString().endsWith(".json")).toList()) {
                JsonNode message = json.readTree(Files.readAllBytes(path));
                if (email.equals(message.path("recipient").asText()) && purpose.equals(message.path("purpose").asText())) {
                    tokens.add(message.path("token").asText());
                }
            }
        }
        return tokens;
    }

    private static String cookie(HttpResponse<?> response) {
        return response.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }

    private static String email() {
        return key() + "@example.invalid";
    }

    public static class Inbox implements QuarkusTestResourceLifecycleManager {
        private Path directory;

        @Override
        public Map<String, String> start() {
            try {
                directory = Files.createTempDirectory("carbonflow-inbox-test-");
                return Map.of("carbonflow.accounts.sandbox-inbox", directory.toString());
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }

        @Override
        public void stop() {
            try (var paths = Files.walk(directory)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                    Files.delete(path);
                }
            } catch (IOException e) {
                throw new IllegalStateException(e);
            }
        }
    }
}
