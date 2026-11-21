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
class ProjectRequirementsIntegrationTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;
    private final HttpClient client = HttpClient.newHttpClient();
    private record Actor(UUID id, String cookie) {}

    @Test
    void projectionFollowsTheDeclarationAndNeverAdmitsE8() throws Exception {
        Actor owner = actor(), outsider = actor();
        String id = project(owner, organization(owner.id()));
        String path = "/v1/projects/" + id + "/technical/requirements";
        assertEquals(401, call("GET", path, null).statusCode());
        assertEquals(404, call("GET", path, outsider.cookie()).statusCode());
        assertEquals(404, call("GET", "/v1/projects/PIL-SYN-BR-001/technical/requirements", owner.cookie()).statusCode());

        var initial = call("GET", path, owner.cookie());
        assertEquals(200, initial.statusCode(), initial.body());
        assertTrue(initial.headers().firstValue("Cache-Control").orElseThrow().contains("no-store"));
        JsonNode empty = json(initial);
        assertFalse(empty.path("openBranch").asBoolean());
        assertEquals("METHODOLOGY_BRANCH", empty.at("/requirements/0/requirementId").asText());
        assertFalse(empty.toString().contains("VMD0049_ROUTE"));
        assertFalse(empty.path("canRunE8").asBoolean());
        assertFalse(empty.has("emissions"));

        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Recarga aberta","country":"BR","candidateMethodology":"VM0038"}
                """).statusCode());
        JsonNode open = json(call("GET", path, owner.cookie()));
        assertTrue(open.path("openBranch").asBoolean());
        assertEquals("ABSENT", finding(open, "NO_DECLARATION").path("state").asText());
        assertEquals("MINE", finding(open, "NO_DECLARATION").path("group").asText());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", finding(open, "MARKET_ROUTE").path("state").asText());
        assertNotEquals("MINE", finding(open, "MARKET_ROUTE").path("group").asText());
        assertEquals("NOT_INCIDENT", finding(open, "ED_EC").path("state").asText());
        assertFalse(finding(open, "ED_EC").path("blocksExAntePd").asBoolean());
        assertFalse(open.path("exAnteRequiresMeasuredEnergy").asBoolean());
        assertFalse(open.path("canIssueVcu").asBoolean());
        assertFalse(open.toString().contains("SIM-"));
        assertFalse(open.findValuesAsText("ownerRole").stream().anyMatch(role -> role.contains("@")));

        String withoutProof = """
            {"unrestrictedBevAccess":true,"restrictedToIdentifiedVehicles":false,
             "privateLand":true,"chargerType":"DCFC","propulsion":"BEV",
             "electricitySource":"SIN","associatedInfrastructure":false,"v2g":false,
             "chargingOwner":"Titular secreto","carbonRightsContractRef":"CONTRACT-001",
             "driverDisclosureChannel":"NOT_DECLARED","doubleClaimingCheck":"UNKNOWN",
             "effectiveFrom":"2027-02-01"}
            """;
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"0\"", withoutProof).statusCode());
        JsonNode gaps = json(call("GET", path, owner.cookie()));
        assertEquals("NEEDS_EVIDENCE", finding(gaps, "DRIVER_DISCLOSURE").path("state").asText());
        assertFalse(gaps.findValuesAsText("requirementId").contains("NO_DECLARATION"));

        String withProof = withoutProof.replace("\"NOT_DECLARED\"", "\"ON_CHARGER\"")
            .replace("\"UNKNOWN\"", "\"NO_OVERLAP_DECLARED\"")
            .replace("\"effectiveFrom\"",
                "\"driverDisclosureEvidenceRef\":\"PHOTO-2027\",\"driverDisclosureEffectiveOn\":\"2027-01-15\",\"effectiveFrom\"");
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/open-applicability",
            owner.cookie(), key(), "\"1\"", withProof).statusCode());
        JsonNode declared = json(call("GET", path, owner.cookie()));
        assertFalse(declared.findValuesAsText("requirementId").contains("DRIVER_DISCLOSURE"));
        assertFalse(declared.findValuesAsText("requirementId").contains("OWNER_CONTRACT"));
        assertEquals("AWAITING_NORMATIVE_EVALUATION", finding(declared, "REGULATORY_SURPLUS").path("state").asText());
        assertEquals("NOT_ADMITTED", finding(declared, "EVR").path("state").asText());
        assertFalse(finding(declared, "EVR").path("admitsE8").asBoolean());
        assertFalse(declared.path("canRunE8").asBoolean());
    }

    @Test
    void otherMethodologyAndRevokedCalculationGrantStayClosed() throws Exception {
        Actor owner = actor(), calculator = actor(), outsider = actor();
        UUID org = organization(owner.id());
        String id = project(owner, org);
        String path = "/v1/projects/" + id + "/technical/requirements";
        assertEquals(200, call("PUT", "/v1/projects/" + id + "/technical/intake", owner.cookie(),
            key(), "\"0\"", """
                {"activity":"Outra atividade","country":"BR","candidateMethodology":"ACM0002"}
                """).statusCode());
        JsonNode foreign = json(call("GET", path, owner.cookie()));
        assertFalse(foreign.path("openBranch").asBoolean());
        assertEquals("OUT_OF_SCOPE", foreign.at("/requirements/0/state").asText());
        assertFalse(foreign.toString().contains("VMD0049_ROUTE"));
        assertFalse(foreign.toString().contains("\"EVR\""));

        UUID grant = UUID.randomUUID();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_project_collaborator_grant
                  (id,project_id,organization_id,environment,identity_id,granted_by,
                   role,activities,valid_until)
                VALUES (?, ?, ?, 'SANDBOX', ?, ?, 'PREP', ARRAY['CALCULATION']::text[],
                   statement_timestamp()+interval '3 days')
                """, grant, id, org, calculator.id(), owner.id());
            return null;
        });
        JsonNode limited = json(call("GET", path, calculator.cookie()));
        assertFalse(limited.toString().contains("Titular"));
        assertFalse(limited.findValuesAsText("requirementId").contains("DRIVER_DISCLOSURE"));
        assertEquals(404, call("GET", path, outsider.cookie()).statusCode());
        database.transaction(c -> {
            update(c, """
                UPDATE app_project_collaborator_grant
                SET revoked_at=statement_timestamp(),revoked_by=? WHERE id=?
                """, owner.id(), grant);
            return null;
        });
        assertEquals(404, call("GET", path, calculator.cookie()).statusCode());
        assertTrue(database.read(c -> first(c, """
            SELECT count(*) FROM app_project_technical_access_event
            WHERE actor_id=? AND target_digest=? AND action='VIEW_JOURNEY' AND outcome='DENIED'
            """, r -> r.getLong(1), calculator.id(), AccessSecrets.digest(id))) >= 1L);
    }

    private static JsonNode finding(JsonNode view, String id) {
        for (var item : view.path("requirements")) {
            if (id.equals(item.path("requirementId").asText())) return item;
        }
        fail("requisito ausente: " + id + " em " + view);
        return null;
    }

    private Actor actor() {
        UUID id = UUID.randomUUID();
        String token = AccessSecrets.newToken();
        database.transaction(c -> {
            update(c, """
                INSERT INTO app_identity(id,email_normalized,display_name,status,email_verified_at)
                VALUES (?,?,'Diagnóstico teste','ACTIVE',statement_timestamp())
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
            update(c, "INSERT INTO app_organization(id,environment,display_name) VALUES (?,'SANDBOX','Diagnóstico teste')", id);
            update(c, "INSERT INTO app_organization_membership(organization_id,identity_id,role) VALUES (?,?,'DRAFT_OWNER')", id, owner);
            return null;
        });
        return id;
    }

    private String project(Actor owner, UUID org) throws Exception {
        var result = call("POST", "/v1/projects", owner.cookie(), key(), null,
            "{\"organizationId\":\"" + org + "\",\"title\":\"Projeto do diagnóstico\"}");
        assertEquals(201, result.statusCode(), result.body());
        return json(result).path("projectId").asText();
    }

    private static String key() { return UUID.randomUUID().toString(); }

    private HttpResponse<String> call(String method, String path, String cookie) throws Exception {
        return call(method, path, cookie, null, null, null);
    }

    private HttpResponse<String> call(String method, String path, String cookie, String key,
            String match, String body) throws Exception {
        var request = HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
        if (cookie != null) request.header("Cookie", cookie);
        if (key != null) request.header("Idempotency-Key", key);
        if (match != null) request.header("If-Match", match);
        if (body != null) request.header("Content-Type", "application/json");
        return client.send(request.method(method, body == null
            ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(),
            HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode json(HttpResponse<String> response) throws Exception {
        return mapper.readTree(response.body());
    }
}
