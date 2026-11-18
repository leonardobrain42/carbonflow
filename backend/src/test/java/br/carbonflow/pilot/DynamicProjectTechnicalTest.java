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
class DynamicProjectTechnicalTest {
    private static final String PROJECTS = "/v1/projects";

    @Inject PilotDatabase database;
    @Inject ObjectMapper json;
    @TestHTTPResource URL base;

    private final HttpClient client = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    @Test
    void accountProjectMovesFromIntakeToPreliminaryDossierWithoutGrantingE8Authority() throws Exception {
        Actor owner = actor();
        UUID org = organization(owner.id());
        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Recarga comunitária\",\"description\":\"Estudo inicial\"}");
        assertEquals(201, created.statusCode(), created.body());
        String project = body(created).path("projectId").asText();
        String technical = PROJECTS + "/" + project + "/technical";

        assertEquals(200, call("GET", technical + "/capabilities", owner.cookie(), null, null, null).statusCode());
        JsonNode caps = body(call("GET", technical + "/capabilities", owner.cookie(), null, null, null));
        assertEquals("NOT_ENABLED", caps.at("/modules/calculation").asText());
        assertEquals("NOT_ENABLED", caps.at("/modules/externalActs").asText());
        assertEquals("PREPARATORY_ONLY", caps.at("/modules/intake").asText());
        assertEquals(204, call("GET", technical + "/preliminary-dossier", owner.cookie(), null, null, null).statusCode());

        var blank = call("GET", technical + "/intake", owner.cookie(), null, null, null);
        assertEquals(200, blank.statusCode());
        assertEquals("\"0\"", blank.headers().firstValue("ETag").orElseThrow());
        assertEquals("NAO_INICIADO", body(blank).path("state").asText());
        assertTrue(body(blank).path("data").isNull());

        String intake = """
                {"activity":" Operação de recarga de veículos elétricos ",
                "country":"BR","state":"DF","locality":"Brasília",
                "candidateMethodology":"VM0038","referenceActDate":"2027-09-27",
                "notes":"Projeto ainda em estudo"}
                """;
        String saveKey = key();
        HttpRequest command = request("PUT", technical + "/intake", owner.cookie(), saveKey, "\"0\"", intake);
        var first = client.sendAsync(command, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(command, HttpResponse.BodyHandlers.ofString());
        var saved = first.join();
        var replay = second.join();
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals(200, replay.statusCode(), replay.body());
        assertEquals(body(saved), body(replay));
        assertEquals("\"1\"", saved.headers().firstValue("ETag").orElseThrow());
        assertEquals("RASCUNHO", body(saved).path("state").asText());
        assertEquals("NAO_AVALIADO", body(saved).path("reviewState").asText());
        assertEquals("Operação de recarga de veículos elétricos",
                body(saved).at("/data/activity").asText());
        assertTrue(body(saved).path("outstandingGates").toString().contains("PEN-004"));
        assertEquals(1L, count("app_project_intake_draft_revision", project));
        assertEquals(1L, count("app_project_technical_command", project));

        assertEquals(428, call("PUT", technical + "/intake", owner.cookie(), key(), null, intake).statusCode());
        assertEquals(412, call("PUT", technical + "/intake", owner.cookie(), key(), "\"0\"", intake).statusCode());
        var reused = call("PUT", technical + "/intake", owner.cookie(), saveKey, "\"0\"",
                "{\"activity\":\"Outra atividade\"}");
        assertEquals(409, reused.statusCode());
        assertEquals("CHAVE_REUSADA", body(reused).path("code").asText());

        String dossierKey = key();
        var dossier = call("POST", technical + "/preliminary-dossier", owner.cookie(), dossierKey, "\"1\"", "{}");
        assertEquals(201, dossier.statusCode(), dossier.body());
        assertEquals("\"1\"", dossier.headers().firstValue("ETag").orElseThrow());
        assertEquals("RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL", body(dossier).path("state").asText());
        assertEquals("PD_PREPARATORY_SCAFFOLD", body(dossier).path("documentKind").asText());
        assertEquals("SRC-007_PD_v5.0B.docx", body(dossier).path("sourceTemplate").asText());
        assertEquals(1, body(dossier).path("intakeVersion").asInt());
        assertEquals(8, body(dossier).path("sections").size());
        assertTrue(body(dossier).path("blockers").size() >= 2);
        assertEquals(body(dossier), body(call("POST", technical + "/preliminary-dossier",
                owner.cookie(), dossierKey, "\"1\"", "{}")));
        assertEquals(body(dossier), body(call("GET", technical + "/preliminary-dossier",
                owner.cookie(), null, null, null)));
        assertEquals(409, call("POST", technical + "/preliminary-dossier",
                owner.cookie(), key(), "\"1\"", "{}").statusCode());

        var changed = call("PUT", technical + "/intake", owner.cookie(), key(), "\"1\"",
                "{\"activity\":\"Nova descrição de atividade\",\"country\":\"BR\","
                        + "\"state\":\"DF\",\"candidateMethodology\":null}");
        assertEquals(200, changed.statusCode(), changed.body());
        assertEquals(2, body(changed).path("version").asInt());
        var next = call("POST", technical + "/preliminary-dossier", owner.cookie(), key(), "\"2\"", "{}");
        assertEquals(201, next.statusCode());
        assertEquals(2, body(next).path("revision").asInt());
        assertEquals("A_DEFINIR_APOS_TRIAGEM", body(next).path("sourceTemplate").asText());
        assertEquals(2L, count("app_project_intake_draft_revision", project));
        assertEquals(2L, count("app_project_preliminary_dossier", project));
        assertEquals(1, body(dossier).path("revision").asInt());
        assertEquals("SANDBOX", body(next).path("environment").asText());

        // The dynamic process never writes a pilot E8 intake or dossier.
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM pilot_intake_revision WHERE project_id = ?",
                row -> row.getLong(1), project)).longValue());
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM pilot_document_dossier WHERE project_id = ?",
                row -> row.getLong(1), project)).longValue());
    }

    @Test
    void rejectsCrossOrganizationAndSameOrganizationOtherCreatorWithOpaqueAudit() throws Exception {
        Actor owner = actor();
        Actor peer = actor();
        Actor outsider = actor();
        UUID organization = organization(owner.id());
        membership(organization, peer.id());
        organization(outsider.id());

        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + organization + "\",\"title\":\"Proposta confidencial\"}");
        assertEquals(201, created.statusCode());
        String project = body(created).path("projectId").asText();
        String technical = PROJECTS + "/" + project + "/technical";

        assertEquals(404, call("GET", technical + "/intake", peer.cookie(), null, null, null).statusCode());
        assertEquals(404, call("GET", technical + "/intake", outsider.cookie(), null, null, null).statusCode());
        assertEquals(404, call("POST", technical + "/preliminary-dossier", outsider.cookie(),
                key(), "\"0\"", "{}").statusCode());
        assertEquals(404, call("GET", technical + "/capabilities", peer.cookie(), null, null, null).statusCode());
        assertEquals(401, call("GET", technical + "/intake", null, null, null, null).statusCode());
        assertEquals(404, call("GET", PROJECTS + "/PIL-SYN-BR-001/technical/intake",
                owner.cookie(), null, null, null).statusCode());

        long denied = database.read(connection -> first(connection, """
                SELECT count(*) FROM app_project_technical_access_event
                WHERE actor_id = ? AND target_digest = ? AND outcome = 'DENIED'
                """, row -> row.getLong(1), outsider.id(), AccessSecrets.digest(project)));
        assertTrue(denied >= 2, "Cross-org denied attempts need an independently durable audit entry.");

        database.transaction(connection -> update(connection, """
                UPDATE app_organization_membership SET revoked_at = statement_timestamp()
                WHERE organization_id = ? AND identity_id = ?
                """, organization, owner.id()));
        assertEquals(404, call("GET", technical + "/intake", owner.cookie(), null, null, null).statusCode());
        assertEquals(404, call("PUT", technical + "/intake", owner.cookie(), key(), "\"0\"",
                "{\"activity\":\"Não autorizado\"}").statusCode());
        long deniedRevoked = database.read(connection -> first(connection, """
                SELECT count(*) FROM app_project_technical_access_event
                WHERE actor_id = ? AND target_digest = ? AND outcome = 'DENIED'
                """, row -> row.getLong(1), owner.id(), AccessSecrets.digest(project)));
        assertTrue(deniedRevoked >= 2);
    }

    @Test
    void forbidsDossierWithoutIntakeAndPreservesOriginalProjectMetadata() throws Exception {
        Actor actor = actor();
        UUID organization = organization(actor.id());
        var created = call("POST", PROJECTS, actor.cookie(), key(), null,
                "{\"organizationId\":\"" + organization + "\",\"title\":\"Projeto em planejamento\"}");
        assertEquals(201, created.statusCode());
        String project = body(created).path("projectId").asText();
        String technical = PROJECTS + "/" + project + "/technical";
        assertEquals(409, call("POST", technical + "/preliminary-dossier", actor.cookie(),
                key(), "\"0\"", "{}").statusCode());
        assertEquals(400, call("PUT", technical + "/intake", actor.cookie(), key(), "\"0\"",
                "{\"activity\":\"  \"}").statusCode());
        assertEquals(0L, count("app_project_intake_draft_revision", project));
        assertEquals(0L, count("app_project_preliminary_dossier", project));
        var metadata = call("GET", PROJECTS + "/" + project + "/metadata", actor.cookie(), null, null, null);
        assertEquals(200, metadata.statusCode());
        assertEquals(1, body(metadata).path("version").asInt());
        assertEquals("DRAFT", body(metadata).path("state").asText());
    }

    @Test
    void planningResponsibilitiesAndExperienceArePrivateAndKeepTheSameTechnicalGates() throws Exception {
        Actor owner = actor();
        Actor coworker = actor();
        UUID org = organization(owner.id());
        membership(org, coworker.id());
        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto com equipe em planejamento\"}");
        assertEquals(201, created.statusCode(), created.body());
        String project = PROJECTS + "/" + body(created).path("projectId").asText();
        String experience = project + "/experience";
        var blank = call("GET", experience, owner.cookie(), null, null, null);
        assertEquals(200, blank.statusCode(), blank.body());
        assertEquals("GUIDED", body(blank).at("/preferences/mode").asText());
        var before = body(call("GET", project + "/technical/intake", owner.cookie(), null, null, null));
        assertEquals("GUIDED", before.path("experienceMode").asText());
        String planning = """
                {"mode":"TECHNICAL","declaredProfile":"TECHNICAL","declaredCompetence":"Experiência declarada.",
                "responsibilities":{"prep":"Equipe de engenharia"}}
                """;
        var cmd = request("PUT", experience, owner.cookie(), null, "\"0\"", planning);
        var first = client.sendAsync(cmd, HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(cmd, HttpResponse.BodyHandlers.ofString());
        var saved = first.join();
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("\"1\"", saved.headers().firstValue("ETag").orElseThrow());
        assertEquals(body(saved), body(second.join()));
        assertEquals("Equipe de engenharia", body(saved).at("/preferences/responsibilities/prep").asText());
        assertTrue(body(saved).at("/preferences/responsibilities/rev").isNull());
        assertEquals(412, call("PUT", experience, owner.cookie(), null, "\"0\"",
                "{\"mode\":\"GUIDED\"}").statusCode());
        assertEquals(404, call("GET", experience, coworker.cookie(), null, null, null).statusCode());
        assertEquals(404, call("PUT", experience, coworker.cookie(), null, "\"1\"", planning).statusCode());
        var after = body(call("GET", project + "/technical/intake", owner.cookie(), null, null, null));
        assertEquals("TECHNICAL", after.path("experienceMode").asText());
        assertEquals(before.path("outstandingGates"), after.path("outstandingGates"));
        assertEquals(before.path("reviewState"), after.path("reviewState"));
        assertEquals("NOT_ENABLED", body(call("GET", project + "/technical/capabilities",
                owner.cookie(), null, null, null)).at("/modules/externalActs").asText());
        assertEquals("DRAFT", body(call("GET", project + "/metadata", owner.cookie(), null, null, null)).path("state").asText());
    }

    @Test
    void plansExternalContributorsAndFlagsSameIdentityAcrossRoles() throws Exception {
        Actor owner = actor();
        Actor peer = actor();
        UUID org = organization(owner.id());
        membership(org, peer.id());
        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Planejamento de convites\"}");
        assertEquals(201, created.statusCode(), created.body());
        String projectId = body(created).path("projectId").asText();
        String plans = PROJECTS + "/" + projectId + "/invitation-plans";
        String preparer = plans + "/" + UUID.randomUUID();
        String reviewer = plans + "/" + UUID.randomUUID();
        assertEquals(0, body(call("GET", plans, owner.cookie(), null, null, null)).path("plans").size());
        String input = """
                {"email":" EXTERNO@example.invalid ","role":"PREP","activities":["BASELINE"],"deadline":"2030-01-15",
                 "competence":"Experiência declarada","declaredConflict":"UNKNOWN"}
                """;
        var saved = call("PUT", preparer, owner.cookie(), null, "\"0\"", input);
        assertEquals(200, saved.statusCode(), saved.body());
        assertEquals("PLANNED", body(saved).at("/plans/0/state").asText());
        assertEquals("externo@example.invalid", body(saved).at("/plans/0/details/email").asText());
        assertEquals("REVIEW_REQUIRED", body(saved).at("/plans/0/independence").asText());
        assertEquals(body(saved), body(call("PUT", preparer, owner.cookie(), null, "\"0\"", input)));

        var overlap = call("PUT", reviewer, owner.cookie(), null, "\"0\"", input.replace("PREP", "REV"));
        assertEquals(200, overlap.statusCode(), overlap.body());
        var matches = body(overlap).path("plans").findValuesAsText("independence");
        assertEquals(java.util.List.of("SAME_PREPARER_AND_REVIEWER", "SAME_PREPARER_AND_REVIEWER"), matches);
        assertEquals(404, call("GET", plans, peer.cookie(), null, null, null).statusCode());
        assertEquals(404, call("PUT", reviewer, peer.cookie(), null, "\"1\"", input).statusCode());

        String edited = input.replace("PREP", "REV").replace("EXTERNO@", "SEGUNDO@");
        assertEquals(412, call("PUT", reviewer, owner.cookie(), null, "\"0\"", edited).statusCode());
        var split = call("PUT", reviewer, owner.cookie(), null, "\"1\"", edited);
        assertEquals(200, split.statusCode(), split.body());
        assertEquals(java.util.List.of("REVIEW_REQUIRED", "REVIEW_REQUIRED"),
                body(split).path("plans").findValuesAsText("independence"));
        assertEquals(3L, database.read(connection -> first(connection,
                "SELECT count(*) FROM app_project_invitation_plan_revision WHERE project_id = ?",
                row -> row.getLong(1), projectId)).longValue());
        assertEquals(0L, database.read(connection -> first(connection,
                "SELECT count(*) FROM app_identity WHERE email_normalized = 'externo@example.invalid'",
                row -> row.getLong(1))).longValue());
        assertEquals(2L, database.read(connection -> first(connection,
                "SELECT count(*) FROM app_organization_membership WHERE organization_id = ?",
                row -> row.getLong(1), org)).longValue());
    }

    @Test
    void keepsAuthorityRequestsSeparateAndPreservesTheWithdrawalHistory() throws Exception {
        Actor owner = actor();
        Actor peer = actor();
        UUID org = organization(owner.id());
        membership(org, peer.id());
        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto com pedido de poderes\"}");
        assertEquals(201, created.statusCode(), created.body());
        String project = body(created).path("projectId").asText();
        String path = PROJECTS + "/" + project + "/authority-requests";
        String firstPath = path + "/" + UUID.randomUUID();
        String duplicatePath = path + "/" + UUID.randomUUID();
        String input = """
                {"power":"REPRESENT_PROJECT","representationBasis":"Ata registrada em reunião.",
                 "evidenceReference":"Ata 2026-10-15"}
                """;
        var first = client.sendAsync(request("PUT", firstPath, owner.cookie(), null, null, input),
                HttpResponse.BodyHandlers.ofString());
        var second = client.sendAsync(request("PUT", duplicatePath, owner.cookie(), null, null, input),
                HttpResponse.BodyHandlers.ofString());
        var a = first.join();
        var b = second.join();
        assertTrue(a.statusCode() == 200 && b.statusCode() == 409 || a.statusCode() == 409 && b.statusCode() == 200);
        String registered = a.statusCode() == 200 ? firstPath : duplicatePath;
        assertEquals(404, call("GET", path, peer.cookie(), null, null, null).statusCode());
        assertEquals(404, call("POST", registered + "/withdraw", peer.cookie(), null, "\"1\"", null).statusCode());
        assertEquals(200, call("PUT", registered, owner.cookie(), null, null, input).statusCode());
        assertEquals(409, call("PUT", registered, owner.cookie(), null, null, input.replace("Ata registrada", "Outra ata")).statusCode());
        var invitePower = call("PUT", path + "/" + UUID.randomUUID(), owner.cookie(), null, null,
                input.replace("REPRESENT_PROJECT", "SEND_INVITATIONS"));
        assertEquals(200, invitePower.statusCode(), invitePower.body());
        assertEquals(2, body(invitePower).path("requests").size());
        assertEquals(412, call("POST", registered + "/withdraw", owner.cookie(), null, "\"0\"", null).statusCode());
        var withdrawal = call("POST", registered + "/withdraw", owner.cookie(), null, "\"1\"", null);
        assertEquals(200, withdrawal.statusCode(), withdrawal.body());
        assertTrue(body(withdrawal).path("requests").findValuesAsText("state").contains("WITHDRAWN"));
        assertEquals(body(withdrawal), body(call("POST", registered + "/withdraw", owner.cookie(), null, "\"1\"", null)));
        assertEquals(3L, database.read(connection -> first(connection,
                "SELECT count(*) FROM app_project_authority_request_revision WHERE project_id = ?",
                row -> row.getLong(1), project)).longValue());
        assertEquals("NOT_ENABLED", body(call("GET", PROJECTS + "/" + project + "/technical/capabilities",
                owner.cookie(), null, null, null)).at("/modules/externalActs").asText());
    }

    @Test
    void technicalAdaptersRespectRoleActivityAndRevocation() throws Exception {
        Actor owner = actor();
        Actor reviewer = actor();
        Actor preparer = actor();
        UUID org = organization(owner.id());
        var created = call("POST", PROJECTS, owner.cookie(), key(), null,
                "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto compartilhado\"}");
        assertEquals(201, created.statusCode(), created.body());
        String project = body(created).path("projectId").asText();
        String root = PROJECTS + "/" + project;
        String intake = root + "/technical/intake";
        String dossier = root + "/technical/preliminary-dossier";
        UUID revGrant = grant(project, org, reviewer, owner, "REV", "DOCUMENTATION");
        grant(project, org, preparer, owner, "PREP", "INTAKE");

        assertEquals(200, call("GET", root + "/metadata", reviewer.cookie(), null, null, null).statusCode());
        assertEquals(404, call("GET", root + "/triage", reviewer.cookie(), null, null, null).statusCode());
        var preparedInput = call("PUT", intake, preparer.cookie(), key(), "\"0\"",
                "{\"activity\":\"Estudo de recarga\"}");
        assertEquals(200, preparedInput.statusCode(), preparedInput.body());
        assertEquals(404, call("POST", dossier, preparer.cookie(), key(), "\"1\"", null).statusCode());
        assertEquals(404, call("PUT", intake, reviewer.cookie(), key(), "\"1\"",
                "{\"activity\":\"Alteração indevida\"}").statusCode());
        assertEquals(404, call("POST", dossier, reviewer.cookie(), key(), "\"1\"", null).statusCode());
        assertEquals(201, call("POST", dossier, owner.cookie(), key(), "\"1\"", null).statusCode());
        assertEquals(200, call("GET", dossier, reviewer.cookie(), null, null, null).statusCode());
        var download = call("GET", dossier + "/download", reviewer.cookie(), null, null, null);
        assertEquals(200, download.statusCode(), download.body());
        assertTrue(download.headers().firstValue("Content-Disposition").orElseThrow().startsWith("attachment;"));
        assertEquals("RASCUNHO_PRELIMINAR_NAO_ADMISSIVEL", body(download).path("state").asText());
        assertEquals(404, call("GET", dossier + "/download", preparer.cookie(), null, null, null).statusCode());

        database.transaction(c -> update(c, """
                UPDATE app_project_collaborator_grant SET revoked_at = statement_timestamp(), revoked_by = ? WHERE id = ?
                """, owner.id(), revGrant));
        for (String url : java.util.List.of(root + "/metadata", intake, dossier, dossier + "/download")) {
            assertEquals(404, call("GET", url, reviewer.cookie(), null, null, null).statusCode());
        }
        assertEquals(1L, database.read(c -> first(c,
                "SELECT count(*) FROM app_project_intake_draft_revision WHERE project_id = ? AND actor_id = ?",
                row -> row.getLong(1), project, preparer.id())).longValue());
    }

    private UUID grant(String project, UUID org, Actor recipient, Actor owner, String role, String activity) {
        UUID id = UUID.randomUUID();
        database.transaction(c -> update(c, """
                INSERT INTO app_project_collaborator_grant(id,project_id,organization_id,environment,identity_id,
                    granted_by,role,activities,valid_until)
                VALUES(?,?,?,'SANDBOX',?,?,?,ARRAY[?]::text[],statement_timestamp()+interval '1 day')
                """, id, project, org, recipient.id(), owner.id(), role, activity));
        return id;
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(connection -> {
            update(connection, """
                    INSERT INTO app_identity (id, email_normalized, display_name, status, email_verified_at)
                    VALUES (?, ?, 'Conta E8 dinâmica', 'ACTIVE', statement_timestamp())
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
                    VALUES (?, 'SANDBOX', 'Organização E8')
                    """, id);
            membershipRow(connection, id, owner);
            return null;
        });
        return id;
    }

    private void membership(UUID organization, UUID member) {
        database.transaction(connection -> {
            membershipRow(connection, organization, member);
            return null;
        });
    }

    private void membershipRow(java.sql.Connection connection, UUID org, UUID actor) throws java.sql.SQLException {
        update(connection, """
                INSERT INTO app_organization_membership(organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER')
                """, org, actor);
    }

    private long count(String table, String project) {
        String sql = switch (table) {
            case "app_project_intake_draft_revision" -> """
                    SELECT count(*) FROM app_project_intake_draft_revision WHERE project_id = ?
                    """;
            case "app_project_preliminary_dossier" -> """
                    SELECT count(*) FROM app_project_preliminary_dossier WHERE project_id = ?
                    """;
            case "app_project_technical_command" -> """
                    SELECT count(*) FROM app_project_technical_command WHERE project_id = ?
                    """;
            default -> throw new IllegalArgumentException(table);
        };
        return database.read(connection -> first(connection, sql, row -> row.getLong(1), project));
    }

    private HttpResponse<String> call(String method, String path, String cookie,
                                      String key, String etag, String payload) throws Exception {
        return client.send(request(method, path, cookie, key, etag, payload),
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

    private JsonNode body(HttpResponse<String> response) throws Exception {
        return json.readTree(response.body());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
