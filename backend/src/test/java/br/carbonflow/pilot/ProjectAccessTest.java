package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;
import java.util.stream.StreamSupport;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;

import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectAccessTest {
    private static final String PROJECT = "/v1/projects/PIL-SYN-BR-001";
    private static final String OTHER_PROJECT = "/v1/projects/SIM-PROJETO-ISOLAMENTO";
    private static final String SESSION = "/v1/session";

    @TestHTTPResource
    URL base;

    @Inject
    ObjectMapper json;

    @Inject
    PilotDatabase database;

    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    @Test
    void isolatesOrganizationProjectAndRoleAndRejectsCrossOriginCommands() throws Exception {
        String prep = login("PREP");
        String outsider = login("PREP-OUTRA");
        String operation = login("OP");
        var dossier = prepare(PROJECT, prep);
        var item = find(PROJECT, prep, dossier, "PUBLICA", 1);
        String content = artifactUrl(PROJECT, item) + "/content";

        var unauthenticated = get(content, null);
        assertEquals(401, unauthenticated.statusCode());
        assertEquals("no-store", unauthenticated.headers().firstValue("Cache-Control").orElseThrow());
        assertEquals(404, get(PROJECT + "/artifacts", outsider).statusCode());
        assertEquals(404, get(PROJECT + "/runs/latest", outsider).statusCode());
        assertEquals(404, get("/v1/projects/SIM-PROJETO-INTERNO/artifacts", prep).statusCode());
        assertEquals(403, get(content, operation).statusCode());

        var outside = get(content, outsider);
        var unknown = get(artifactUrl(PROJECT, item).replace(item.path("id").asText(), UUID.randomUUID().toString())
                + "/content", outsider);
        assertEquals(404, outside.statusCode());
        assertEquals(withoutRequestId(outside), withoutRequestId(unknown));
        assertFalse(outside.body().contains(item.path("id").asText()));
        assertFalse(outside.body().contains("Memória pública"));

        var own = prepare(OTHER_PROJECT, outsider);
        var ownItem = find(OTHER_PROJECT, outsider, own, "PUBLICA", 1);
        assertEquals(200, get(artifactUrl(OTHER_PROJECT, ownItem) + "/content", outsider).statusCode());
        assertEquals(404, get(artifactUrl(OTHER_PROJECT, item) + "/content", outsider).statusCode());
        assertEquals(404, get(artifactUrl(OTHER_PROJECT, ownItem) + "/preview", prep).statusCode());

        var forgedOrigin = request("POST", PROJECT + "/dossiers", prep, "{\"sample\":\"E8-002@1\"}",
                newKey(), null, "http://estranho.example");
        assertEquals(403, forgedOrigin.statusCode());
        assertEquals("ORIGEM_NEGADA", body(forgedOrigin).path("code").asText());

        var foreignActorHeader = HttpRequest.newBuilder(uri(content)).header("Cookie", outsider)
                .header("X-Actor-Id", "SIM-PREP").GET().build();
        assertEquals(404, client.send(foreignActorHeader, HttpResponse.BodyHandlers.ofString()).statusCode());
        assertEquals(204, request("DELETE", SESSION, prep, null, null, null, null).statusCode());
        assertEquals(401, get(content, prep).statusCode());
    }

    @Test
    void freezesBytesGrantsOnlyExactVariantAndKeepsReceiptAfterRevocation() throws Exception {
        String prep = login("PREP");
        String rev = login("REV");
        String pp = login("PP");
        String vvb = login("VVB");
        String dossier = prepare(PROJECT, prep);
        var publicV1 = find(PROJECT, prep, dossier, "PUBLICA", 1);
        var publicV2 = find(PROJECT, prep, dossier, "PUBLICA", 2);
        var reserved = find(PROJECT, prep, dossier, "RESERVADA", 1);
        String publicUrl = artifactUrl(PROJECT, publicV1);
        var draft = get(publicUrl + "/content", prep);
        assertEquals(200, draft.statusCode());
        assertTrue(draft.body().contains("SIMULAÇÃO"));
        assertFalse(draft.body().contains("SIM-ANEXO-RESERVADO"));
        assertEquals(404, get(publicUrl + "/preview", pp).statusCode());
        assertEquals(404, get(publicUrl + "/content", vvb).statusCode());

        String ownDossier = prepare(PROJECT, rev);
        var own = find(PROJECT, rev, ownDossier, "PUBLICA", 1);
        assertEquals(403, review(prep, dossier, publicV1, "1").statusCode());
        var self = review(rev, ownDossier, own, "1");
        assertEquals(403, self.statusCode());
        assertEquals("AUTOAPROVACAO_NEGADA", body(self).path("code").asText());
        assertEquals("EM_REVISAO", body(get(artifactUrl(PROJECT, own) + "/preview", rev)).at("/artifact/state").asText());
        assertEquals(428, review(rev, dossier, publicV1, null).statusCode());
        assertEquals(200, review(rev, dossier, publicV1, "1").statusCode());
        assertEquals(412, review(rev, dossier, publicV1, "1").statusCode());
        assertEquals(200, review(rev, dossier, publicV2, "1").statusCode());
        assertEquals(200, review(rev, dossier, reserved, "1").statusCode());

        var frozen = get(publicUrl + "/content", pp);
        assertEquals(draft.body(), frozen.body());
        assertEquals("no-store", frozen.headers().firstValue("Cache-Control").orElseThrow());
        assertTrue(frozen.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        var approved = body(get(publicUrl + "/preview", pp)).path("artifact");
        assertEquals("SIM-REV", approved.path("approvedBy").asText());
        assertEquals(FixtureCatalog.sha256(frozen.body().getBytes(java.nio.charset.StandardCharsets.UTF_8)),
                approved.path("sha256").asText());
        assertEquals(publicV1.path("sha256"), approved.path("sha256"));
        assertTrue(get(artifactUrl(PROJECT, reserved) + "/content", pp).body().contains("SIM-ANEXO-RESERVADO"));

        String expiry = OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString();
        assertEquals(404, grant(pp, publicV1, "AUDITORIA_V2", expiry, newKey()).statusCode());
        assertEquals(403, grant(prep, publicV1, "AUDITORIA_V1", expiry, newKey()).statusCode());
        String grantKey = newKey();
        var granted = grant(pp, publicV1, "AUDITORIA_V1", expiry, grantKey);
        assertEquals(201, granted.statusCode(), granted.body());
        assertEquals(body(granted), body(grant(pp, publicV1, "AUDITORIA_V1", expiry, grantKey)));
        assertEquals(409, grant(pp, publicV2, "AUDITORIA_V1", expiry, grantKey).statusCode());
        var duplicate = grant(pp, publicV1, "AUDITORIA_V1", expiry, newKey());
        assertEquals(409, duplicate.statusCode());
        assertEquals("CONCESSAO_VIGENTE", body(duplicate).path("code").asText());
        var grantId = body(granted).at("/grant/id").asText();

        var visible = body(get(PROJECT + "/artifacts?q=Mem%C3%B3ria", vvb));
        assertTrue(items(visible).anyMatch(a -> a.path("id").equals(publicV1.path("id")) && a.path("version").asInt() == 1));
        assertTrue(items(visible).noneMatch(a -> a.path("id").equals(publicV1.path("id")) && a.path("version").asInt() == 2));
        assertEquals(0, body(get(PROJECT + "/artifacts?q=reservado", vvb)).size());
        var preview = body(get(publicUrl + "/preview", vvb));
        assertTrue(preview.at("/artifact/author").isNull());
        assertTrue(preview.at("/artifact/dossierId").isNull());
        assertEquals(frozen.body(), preview.path("text").asText());
        assertEquals(frozen.body(), get(publicUrl + "/content", vvb).body());
        assertEquals(404, get(artifactUrl(PROJECT, publicV2) + "/content", vvb).statusCode());
        assertEquals(404, get(artifactUrl(PROJECT, reserved) + "/preview", vvb).statusCode());
        assertEquals(403, get(PROJECT + "/runs/latest", vvb).statusCode());

        String grantPath = PROJECT + "/grants/" + grantId;
        var receipt = post(grantPath + "/receipts", pp, null, newKey(), "1");
        assertEquals(200, receipt.statusCode(), receipt.body());
        assertEquals("SIM-VVB-A", body(receipt).at("/grant/receipt/issuer").asText());
        assertEquals("SIM-PP", body(receipt).at("/grant/receipt/capturedBy").asText());
        assertEquals(412, post(grantPath + "/revoke", pp, null, newKey(), "1").statusCode());
        var revoked = post(grantPath + "/revoke", pp, null, newKey(), "2");
        assertEquals(200, revoked.statusCode());
        assertEquals(body(receipt).at("/grant/receipt"), body(revoked).at("/grant/receipt"));
        assertEquals(404, get(publicUrl + "/preview", vvb).statusCode());
        assertEquals(404, get(publicUrl + "/content", vvb).statusCode());
        assertTrue(items(body(get(PROJECT + "/artifacts", vvb))).noneMatch(a -> a.path("id").equals(publicV1.path("id"))));
        assertEquals(frozen.body(), get(publicUrl + "/content", pp).body());

        var events = body(get(PROJECT + "/access-events", rev));
        assertTrue(items(events).anyMatch(e -> e.path("action").asText().equals("BAIXAR_ARTEFATO")
                && e.path("actor").asText().equals("SIM-VVB") && e.at("/details/grantId").asText().equals(grantId)));
        assertTrue(items(events).anyMatch(e -> e.path("action").asText().equals("BAIXAR_ARTEFATO")
                && e.path("actor").asText().equals("SIM-VVB") && e.path("outcome").asText().equals("NEGADO")));
        assertTrue(items(events).anyMatch(e -> e.at("/details/code").asText().equals("AUTOAPROVACAO_NEGADA")));
        assertEquals(403, get(PROJECT + "/access-events", vvb).statusCode());
    }

    @Test
    void expiresGrantsAndRechecksMembershipOnExistingSession() throws Exception {
        String prep = login("PREP");
        String rev = login("REV");
        String pp = login("PP");
        String vvb = login("VVB");
        var dossier = prepare(PROJECT, prep);
        var item = find(PROJECT, prep, dossier, "PUBLICA", 1);
        assertEquals(200, review(rev, dossier, item, "1").statusCode());
        var grant = grant(pp, item, "AUDITORIA_V1", OffsetDateTime.now(ZoneOffset.UTC).plusHours(1).toString(), newKey());
        assertEquals(201, grant.statusCode(), grant.body());
        String content = artifactUrl(PROJECT, item) + "/content";
        assertEquals(200, get(content, vvb).statusCode());
        UUID grantId = UUID.fromString(body(grant).at("/grant/id").asText());
        try {
            database.read(connection -> PilotSql.update(connection, """
                    UPDATE pilot_membership SET valid_until = clock_timestamp() WHERE identity_id = 'SIM-VVB'
                    """));
            assertEquals(404, get(content, vvb).statusCode());
        } finally {
            database.read(connection -> PilotSql.update(connection, """
                    UPDATE pilot_membership SET valid_until = 'infinity' WHERE identity_id = 'SIM-VVB'
                    """));
        }
        assertEquals(200, get(content, vvb).statusCode());
        database.read(connection -> PilotSql.update(connection, """
                UPDATE pilot_grant SET granted_at = clock_timestamp() - interval '1 hour', expires_at = clock_timestamp()
                WHERE id = ?
                """, grantId));
        assertEquals(404, get(content, vvb).statusCode());
        assertEquals(404, get(artifactUrl(PROJECT, item) + "/preview", vvb).statusCode());
        assertTrue(items(body(get(PROJECT + "/artifacts", vvb))).noneMatch(a -> a.path("id").equals(item.path("id"))));
    }

    private String prepare(String project, String cookie) throws Exception {
        var response = post(project + "/dossiers", cookie, "{\"sample\":\"E8-002@1\"}", newKey(), null);
        assertEquals(201, response.statusCode(), response.body());
        return body(response).path("id").asText();
    }

    private JsonNode find(String project, String cookie, String dossier, String variant, int version) throws Exception {
        return items(body(get(project + "/artifacts", cookie)))
                .filter(a -> a.path("dossierId").asText().equals(dossier) && a.path("variant").asText().equals(variant)
                        && a.path("version").asInt() == version).findFirst().orElseThrow();
    }

    private HttpResponse<String> review(String cookie, String dossier, JsonNode item, String version) throws Exception {
        return post(PROJECT + "/dossiers/" + dossier + "/actions", cookie, """
                {"transitionId":"TX-E3-08","details":{"artifact":%s}}
                """.formatted(reference(item)), newKey(), version);
    }

    private HttpResponse<String> grant(String cookie, JsonNode item, String purpose, String expiry, String key) throws Exception {
        return post(PROJECT + "/grants", cookie, """
                {"artifact":%s,"purpose":"%s","recipient":"SIM-VVB","expiresAt":"%s"}
                """.formatted(reference(item), purpose, expiry), key, null);
    }

    private String login(String identity) throws Exception {
        var response = post(SESSION, null, """
                {"identity":"SIM-%s","password":"piloto-%s"}
                """.formatted(identity, identity.toLowerCase(java.util.Locale.ROOT)), null, null);
        assertEquals(204, response.statusCode(), response.body());
        String cookie = response.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly; SameSite=Strict"));
        return cookie.split(";", 2)[0];
    }

    private HttpResponse<String> get(String path, String cookie) throws Exception {
        return request("GET", path, cookie, null, null, null, null);
    }

    private HttpResponse<String> post(String path, String cookie, String body, String key, String version) throws Exception {
        return request("POST", path, cookie, body, key, version, null);
    }

    private HttpResponse<String> request(String method, String path, String cookie, String body, String key,
                                         String version, String origin) throws Exception {
        var request = HttpRequest.newBuilder(uri(path)).timeout(Duration.ofSeconds(10));
        if (cookie != null) request.header("Cookie", cookie);
        if (key != null) request.header("Idempotency-Key", key);
        if (version != null) request.header("If-Match", "\"" + version + "\"");
        if (origin != null) request.header("Origin", origin);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private URI uri(String path) {
        return URI.create(base.toString()).resolve(path);
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private JsonNode withoutRequestId(HttpResponse<String> response) throws Exception {
        var object = (com.fasterxml.jackson.databind.node.ObjectNode) body(response);
        object.remove("requestId");
        return object;
    }

    private java.util.stream.Stream<JsonNode> items(JsonNode node) {
        return StreamSupport.stream(node.spliterator(), false);
    }

    private String reference(JsonNode item) {
        return "{\"id\":\"" + item.path("id").asText() + "\",\"version\":" + item.path("version").asInt() + "}";
    }

    private String artifactUrl(String project, JsonNode artifact) {
        return project + "/artifacts/" + artifact.path("id").asText() + "/versions/" + artifact.path("version").asInt();
    }

    private String newKey() {
        return UUID.randomUUID().toString();
    }
}
