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

@QuarkusTest
@QuarkusTestResource(value = ComplianceTest.Settings.class, restrictToAnnotatedClass = true)
class ComplianceTest {
    private static final UUID STAFF = UUID.fromString("ec28782d-712e-4aa8-b3c0-85f90fdd2826");
    private static final UUID OWNER_STAFF = UUID.fromString("7f067263-a45f-4e2a-93ce-26f94bb38258");
    private static final UUID ORG = UUID.fromString("a3c583fe-6279-4ba9-a40f-60bcbe6e4355");
    private static final byte[] OTP = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
    private static final String API = "/v1/compliance";
    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL server;
    private final HttpClient client = HttpClient.newHttpClient();

    @Test
    void mfaAndEvidenceAreBoundToTheStaffAccountSessionOrganizationAndRequest() throws Exception {
        assertEquals("287082", ComplianceStaff.codeAt(OTP, 1));
        String reviewer = actor(STAFF);
        String secondSession = actor(STAFF);
        String owner = actor(OWNER_STAFF);
        String otherOwner = actor(UUID.randomUUID());
        String project = project(owner, OWNER_STAFF, ORG);
        UUID otherId = lookup(otherOwner);
        String unrelated = project(otherOwner, otherId, UUID.randomUUID());
        String requestId = submit(owner, project);
        submit(otherOwner, unrelated);
        String path = "/v1/projects/" + project + "/authority-requests/" + requestId;
        String file = UUID.randomUUID().toString();
        String pdf = "%PDF-1.7\nconteudo sintetico\n%%EOF";
        assertEquals(415, call("PUT", path + "/documents/" + file, owner, "text", "application/octet-stream").statusCode());
        assertEquals(200, call("PUT", path + "/documents/" + file, owner, pdf, "application/octet-stream").statusCode());
        assertNotEquals(pdf, database.read(c -> first(c,
                "SELECT encode(encrypted,'escape') FROM app_compliance_evidence WHERE id = ?",
                row -> row.getString(1), UUID.fromString(file))));
        assertEquals(404, call("GET", path + "/documents/" + file, otherOwner, null, null).statusCode());

        assertEquals(403, call("GET", API + "/queue", reviewer, null, null).statusCode());
        assertEquals(404, call("GET", API + "/session", otherOwner, null, null).statusCode());
        String code = currentCode();
        assertEquals(200, call("POST", API + "/session", reviewer, code, "application/json").statusCode());
        assertEquals(403, call("POST", API + "/session", secondSession, code, "application/json").statusCode());
        assertEquals(403, call("GET", API + "/queue", secondSession, null, null).statusCode());
        var list = call("GET", API + "/queue", reviewer, null, null);
        assertEquals(200, list.statusCode(), list.body());
        assertEquals(java.util.List.of(project), body(list).path("cases").findValuesAsText("projectId"));

        assertEquals(200, call("POST", API + "/session", owner, currentCode(), "application/json").statusCode());
        assertEquals(0, body(call("GET", API + "/queue", owner, null, null)).path("cases").size());
        String download = API + "/projects/" + project + "/requests/" + requestId + "/documents/" + file;
        assertEquals(404, call("GET", download, owner, null, null).statusCode());
        assertEquals(404, call("GET", API + "/projects/" + unrelated + "/requests/"
                + requestId + "/documents/" + file, reviewer, null, null).statusCode());
        var result = call("GET", download, reviewer, null, null);
        assertEquals(200, result.statusCode(), result.body());
        assertEquals(pdf, result.body());
        assertTrue(result.headers().allValues("Cache-Control").stream().anyMatch(value -> value.contains("no-store")));
        assertTrue(result.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertEquals(200, call("POST", path + "/withdraw", owner, null, null, "\"1\"").statusCode());
        assertEquals(404, call("GET", download, reviewer, null, null).statusCode());
        assertEquals(404, call("GET", path + "/documents/" + file, owner, null, null).statusCode());

        String fresh = submit(owner, project);
        String ownerPath = "/v1/projects/" + project + "/authority-requests/" + fresh;
        String staffPath = API + "/projects/" + project + "/requests/" + fresh;
        String decisions = staffPath + "/decisions";
        String complement = "{\"decision\":\"NEEDS_INFORMATION\",\"reason\":\"Anexe a ata que define esse poder.\"}";
        assertEquals(404, call("POST", decisions, owner, complement, "application/json", "\"0\"").statusCode());
        assertEquals(200, call("POST", decisions, reviewer, complement, "application/json", "\"0\"").statusCode());
        var pending = body(call("GET", "/v1/projects/" + project + "/authority-requests", owner, null, null));
        assertEquals(0, pending.path("activePowers").size());
        assertTrue(pending.path("requests").findValuesAsText("decision").contains("NEEDS_INFORMATION"));
        String guest = actor(UUID.randomUUID());
        String root = "/v1/projects/" + project;
        String invitations = root + "/invitations";
        String inbox = "/v1/project-invitations";
        String planId = invitationPlan(owner, root, guest, "[\"INTAKE\",\"DOCUMENTATION\"]");
        String send = invitations + "/plans/" + planId + "/send";
        assertFalse(body(call("GET", invitations, owner, null, null)).path("canSend").asBoolean());
        assertEquals(403, call("POST", send, owner, null, null, "\"1\"").statusCode());

        String proof = UUID.randomUUID().toString();
        String expiry = java.time.Instant.now().plusSeconds(3600).toString();
        UUID docReviewer = UUID.randomUUID();
        actor(docReviewer);
        database.transaction(c -> update(c, """
                INSERT INTO app_project_collaborator_grant(id,project_id,organization_id,environment,identity_id,
                    granted_by,role,activities,valid_until)
                VALUES(?,?,?,'SANDBOX',?,?,'REV',ARRAY['DOCUMENTATION','BASELINE'],statement_timestamp()+interval '1 hour')
                """,UUID.randomUUID(),project,ORG,docReviewer,OWNER_STAFF));
        String competence = API + "/projects/" + project + "/reviewers/" + docReviewer + "/sections/baseline/competence";
        String reference = "{\"reference\":\"Termo interno do revisor\",\"validUntil\":\"" + expiry + "\"}";
        assertEquals(403,call("GET",competence,secondSession,null,null).statusCode());
        assertEquals(404,call("GET",competence,owner,null,null).statusCode());
        assertEquals(200,call("GET",API+"/pd-reviewers",reviewer,null,null).statusCode());
        var confirmed = call("PUT",competence,reviewer,reference,"application/json","\"0\"");
        assertEquals(200,confirmed.statusCode(),confirmed.body());
        assertTrue(body(confirmed).path("active").asBoolean());
        assertEquals(412,call("PUT",competence,reviewer,reference,"application/json","\"0\"").statusCode());
        var revoked = call("PUT",competence,reviewer,"{\"revoke\":true,\"reference\":\"Retirada\"}","application/json","\"1\"");
        assertEquals(200,revoked.statusCode(),revoked.body());
        assertFalse(body(revoked).path("active").asBoolean());
        String representation = submit(owner, project, "REPRESENT_PROJECT");
        String representationProof = UUID.randomUUID().toString();
        assertEquals(200, call("PUT", root + "/authority-requests/" + representation + "/documents/" + representationProof,
                owner, pdf, "application/octet-stream").statusCode());
        var represented = call("POST", API + "/projects/" + project + "/requests/" + representation + "/decisions",
                reviewer, """
                {"decision":"APPROVED","reason":"Representação demonstrada.","documentId":"%s","validUntil":"%s"}
                """.formatted(representationProof, expiry), "application/json", "\"0\"");
        assertEquals(200, represented.statusCode(), represented.body());
        assertFalse(body(call("GET", invitations, owner, null, null)).path("canSend").asBoolean());
        assertEquals(403, call("POST", send, owner, null, null, "\"1\"").statusCode());
        String approval = "{\"decision\":\"APPROVED\",\"reason\":\"Poder administrativo demonstrado.\","
                + "\"documentId\":\"" + proof + "\",\"validUntil\":\"" + expiry + "\"}";
        assertEquals(400, call("POST", decisions, reviewer, approval, "application/json", "\"1\"").statusCode());
        assertEquals(200, call("PUT", ownerPath + "/documents/" + proof, owner, pdf, "application/octet-stream").statusCode());
        var approved = call("POST", decisions, reviewer, approval, "application/json", "\"1\"");
        assertEquals(200, approved.statusCode(), approved.body());
        assertEquals("APPROVED", body(approved).get(0).path("decision").asText());

        String statusPath = "/v1/projects/" + project + "/authority-requests";
        var active = body(call("GET", statusPath, owner, null, null)).path("activePowers");
        assertEquals(java.util.Set.of("REPRESENT_PROJECT", "SEND_INVITATIONS"),
                java.util.Set.copyOf(active.findValuesAsText("power")));
        assertEquals(409, call("POST", ownerPath + "/withdraw", owner, null, null, "\"1\"").statusCode());
        assertEquals(409, call("PUT", ownerPath + "/documents/" + UUID.randomUUID(), owner, pdf,
                "application/octet-stream").statusCode());

        assertTrue(body(call("GET", invitations, owner, null, null)).path("canSend").asBoolean());
        String unsupported = invitationPlan(owner, root, guest, "[\"EVIDENCE\"]");
        assertEquals(409, call("POST", invitations + "/plans/" + unsupported + "/send",
                owner, null, null, "\"1\"").statusCode());
        assertEquals(412, call("POST", send, owner, null, null, "\"0\"").statusCode());
        String key = UUID.randomUUID().toString();
        var sent = call("POST", send, owner, null, null, "\"1\"", key);
        assertEquals(200, sent.statusCode(), sent.body());
        String invite = body(sent).path("id").asText();
        assertEquals("SENT", body(sent).path("state").asText());
        assertEquals(body(sent), body(call("POST", send, owner, null, null, "\"1\"", key)));
        assertEquals(409, call("POST", send, owner, null, null, "\"1\"").statusCode());
        assertEquals(0, body(call("GET", inbox, otherOwner, null, null)).size());
        assertEquals(404, call("POST", inbox + "/" + invite + "/accept", otherOwner, null, null).statusCode());
        assertEquals(404, call("GET", root + "/metadata", guest, null, null).statusCode());
        assertEquals(invite, body(call("GET", inbox, guest, null, null)).get(0).path("id").asText());

        String accept = inbox + "/" + invite + "/accept";
        var accepted = call("POST", accept, guest, null, null);
        assertEquals(200, accepted.statusCode(), accepted.body());
        assertEquals("ACCEPTED", body(accepted).path("state").asText());
        assertEquals(body(accepted), body(call("POST", accept, guest, null, null)));
        assertEquals(404, call("GET", invitations, guest, null, null).statusCode());
        assertEquals(200, call("GET", root + "/metadata", guest, null, null).statusCode());
        String intake = root + "/technical/intake";
        String dossier = root + "/technical/preliminary-dossier";
        var saved = call("PUT", intake, guest, "{\"activity\":\"Preparação externa\"}", "application/json", "\"0\"");
        assertEquals(200, saved.statusCode(), saved.body());
        var prepared = call("POST", dossier, guest, null, null, "\"1\"");
        assertEquals(201, prepared.statusCode(), prepared.body());
        assertEquals(200, call("GET", dossier + "/download", guest, null, null).statusCode());

        String secondGuest = actor(UUID.randomUUID());
        String secondPlan = invitationPlan(owner, root, secondGuest, "[\"INTAKE\"]");
        var secondSent = call("POST", invitations + "/plans/" + secondPlan + "/send", owner, null, null, "\"1\"");
        assertEquals(200, secondSent.statusCode(), secondSent.body());
        String secondInvite = body(secondSent).path("id").asText();

        String revocation = "{\"decision\":\"REVOKED\",\"reason\":\"Houve mudança na representação.\"}";
        assertEquals(412, call("POST", decisions, reviewer, revocation, "application/json", "\"1\"").statusCode());
        var revoked = call("POST", decisions, reviewer, revocation, "application/json", "\"2\"");
        assertEquals(200, revoked.statusCode(), revoked.body());
        assertEquals(java.util.List.of("REVOKED", "APPROVED", "NEEDS_INFORMATION"),
                body(revoked).findValuesAsText("decision"));
        assertEquals(java.util.List.of("REPRESENT_PROJECT"),
                body(call("GET", statusPath, owner, null, null)).path("activePowers").findValuesAsText("power"));
        assertEquals(409, call("POST", inbox + "/" + secondInvite + "/accept", secondGuest, null, null).statusCode());
        String revoke = invitations + "/" + invite + "/revoke";
        assertEquals(404, call("POST", revoke, guest, null, null).statusCode());
        var invitationRevoked = call("POST", revoke, owner, null, null);
        assertEquals(200, invitationRevoked.statusCode(), invitationRevoked.body());
        database.transaction(c -> update(c, "UPDATE app_project SET title = ? WHERE id = ?", "Título posterior", project));
        var revokedInvitation = body(call("GET", inbox, guest, null, null)).get(0);
        assertEquals("REVOKED", revokedInvitation.path("state").asText());
        assertEquals("Projeto sintético", revokedInvitation.path("projectTitle").asText());
        for (String url : java.util.List.of(root + "/metadata", intake, dossier, dossier + "/download")) {
            assertEquals(404, call("GET", url, guest, null, null).statusCode());
        }
        assertEquals(404, call("PUT", intake, guest, "{\"activity\":\"Nova edição\"}", "application/json", "\"1\"").statusCode());
        UUID guestId = lookup(guest);
        assertEquals(1L, database.read(c -> first(c,
                "SELECT count(*) FROM app_project_intake_draft_revision WHERE project_id = ? AND actor_id = ?",
                row -> row.getLong(1), project, guestId)).longValue());
        assertEquals(404, call("GET", ownerPath + "/documents/" + proof, owner, null, null).statusCode());
        assertEquals(404, call("GET", staffPath + "/documents/" + proof, reviewer, null, null).statusCode());
        assertEquals(3, body(call("GET", decisions, reviewer, null, null)).size());
    }

    private String invitationPlan(String owner, String projectRoot, String guest, String activities) throws Exception {
        String plan = UUID.randomUUID().toString();
        String payload = """
                {"email":"%s@example.invalid","role":"PREP","activities":%s,"declaredConflict":"NONE_DECLARED"}
                """.formatted(lookup(guest), activities);
        var response = call("PUT", projectRoot + "/invitation-plans/" + plan, owner, payload, "application/json", "\"0\"");
        assertEquals(200, response.statusCode(), response.body());
        return plan;
    }

    private String currentCode() {
        long epoch = database.read(c -> first(c, "SELECT floor(extract(epoch from statement_timestamp()) / 30)::bigint",
                row -> row.getLong(1)));
        return "{\"code\":\"" + ComplianceStaff.codeAt(OTP, epoch) + "\"}";
    }

    private String submit(String owner, String project) throws Exception {
        return submit(owner, project, "SEND_INVITATIONS");
    }

    private String submit(String owner, String project, String power) throws Exception {
        String id = UUID.randomUUID().toString();
        var response = call("PUT", "/v1/projects/" + project + "/authority-requests/" + id, owner,
                """
                {"power":"%s","representationBasis":"Ata sintética","evidenceReference":"DOC-SIM"}
                """.formatted(power), "application/json");
        assertEquals(200, response.statusCode(), response.body());
        return id;
    }

    private String project(String cookie, UUID identity, UUID org) throws Exception {
        database.transaction(c -> {
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES(?, 'SANDBOX','Org sintética')", org);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES(?,?,'DRAFT_OWNER')", org, identity);
            return null;
        });
        var response = call("POST", "/v1/projects", cookie,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto sintético\"}", "application/json");
        assertEquals(201, response.statusCode(), response.body());
        return body(response).path("projectId").asText();
    }

    private String actor(UUID id) {
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

    private UUID lookup(String token) {
        return database.read(c -> first(c, "SELECT identity_id FROM app_account_session WHERE token_hash = ?",
                row -> row.getObject(1, UUID.class), AccessSecrets.digest(token)));
    }

    private HttpResponse<String> call(String method, String path, String token, String data, String media) throws Exception {
        return call(method, path, token, data, media, null);
    }

    private HttpResponse<String> call(String method, String path, String token, String data, String media, String etag) throws Exception {
        return call(method, path, token, data, media, etag, UUID.randomUUID().toString());
    }

    private HttpResponse<String> call(String method, String path, String token, String data, String media, String etag,
                                      String key) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(server.toExternalForm()).resolve(path))
                .header("Cookie", AccountAccess.COOKIE + "=" + token)
                .header("Idempotency-Key", key);
        if (media != null) request.header("Content-Type", media);
        if (etag != null) request.header("If-Match", etag);
        return client.send(request.method(method, data == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(data)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    public static class Settings extends AccountAccessTest.Inbox {
        @Override
        public Map<String, String> start() {
            var result = new HashMap<>(super.start());
            String secret = Base64.getUrlEncoder().withoutPadding().encodeToString(OTP);
            result.put("carbonflow.compliance.staff", STAFF + ":" + secret + ":" + ORG + ";"
                    + OWNER_STAFF + ":" + secret + ":" + ORG);
            result.put("carbonflow.compliance.document-key", Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]));
            return result;
        }
    }
}
