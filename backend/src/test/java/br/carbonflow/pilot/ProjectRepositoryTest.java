package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.update;
import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.net.URL;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.inject.Inject;

import com.fasterxml.jackson.databind.ObjectMapper;

import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import org.junit.jupiter.api.Test;

@QuarkusTest
class ProjectRepositoryTest {
    private static final String PILOT = "PIL-SYN-BR-001";
    private static final String INSERT_PROJECT = """
            INSERT INTO app_project (id, organization_id, environment, title, created_by)
            VALUES (?, ?, ?, 'Rascunho privado', ?)
            """;

    @Inject
    ProjectRepository projects;
    @Inject
    PilotDatabase database;
    @Inject
    ObjectMapper json;
    @TestHTTPResource
    URL base;

    @Test
    void isolatesPrivateDraftsFromOtherMembersOrganizationsAndPilot() throws Exception {
        UUID owner = UUID.randomUUID();
        UUID colleague = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID otherOrganization = UUID.randomUUID();
        String draft = UUID.randomUUID().toString();

        database.transaction(connection -> {
            identity(connection, owner);
            identity(connection, colleague);
            organization(connection, organization);
            organization(connection, otherOrganization);
            membership(connection, organization, owner);
            membership(connection, organization, colleague);
            membership(connection, otherOrganization, owner);
            return null;
        });
        assertTrue(projects.listDrafts(owner, organization).isEmpty());
        database.transaction(connection -> update(connection, INSERT_PROJECT, draft, organization, "SANDBOX", owner));

        var expected = new ProjectContext.Draft(draft, organization, "SANDBOX", "DRAFT", 1);
        assertEquals(List.of(expected), projects.listDrafts(owner, organization));
        assertEquals(expected, projects.findDraft(owner, organization, draft).orElseThrow());
        assertTrue(projects.findDraft(colleague, organization, draft).isEmpty());
        assertTrue(projects.listDrafts(colleague, organization).isEmpty());
        assertTrue(projects.findDraft(owner, otherOrganization, draft).isEmpty());
        assertTrue(projects.findDraft(owner, organization, PILOT).isEmpty());

        try (var client = HttpClient.newHttpClient()) {
            var login = client.send(request("/v1/session").header("Content-Type", "application/json")
                    .POST(HttpRequest.BodyPublishers.ofString("""
                            {"identity":"SIM-PP","password":"piloto-pp"}
                            """)).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(204, login.statusCode());
            String cookie = login.headers().firstValue("Set-Cookie").orElseThrow().split(";", 2)[0];
            var directory = client.send(request("/v1/projects").header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, directory.statusCode());
            var listed = json.readTree(directory.body());
            assertEquals(json.readTree("""
                    {"project":"PIL-SYN-BR-001","organization":"ORG-PILOTO","environment":"SIMULACAO","roles":["PP"]}
                    """), listed.get(0));
            for (var item : listed) {
                assertEquals("SIMULACAO", item.path("environment").asText());
                assertEquals("ORG-PILOTO", item.path("organization").asText());
                assertNotEquals(draft, item.path("project").asText());
                assertFalse(item.has("powerProof"));
            }
            var direct = client.send(request("/v1/projects/" + draft).header("Cookie", cookie).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, direct.statusCode());
            assertEquals("RECURSO_NAO_VISIVEL", json.readTree(direct.body()).path("code").asText());
        }

        var now = OffsetDateTime.now();
        setMembershipDates(organization, owner, now.minusHours(2), now.minusHours(1));
        assertTrue(projects.findDraft(owner, organization, draft).isEmpty());
        setMembershipDates(organization, owner, now.plusHours(1), null);
        assertTrue(projects.findDraft(owner, organization, draft).isEmpty());
        setMembershipDates(organization, owner, now.minusHours(1), null);
        assertTrue(projects.findDraft(owner, organization, draft).isPresent());
        database.transaction(connection -> update(connection, "UPDATE app_identity SET status = 'DISABLED' WHERE id = ?", owner));
        assertTrue(projects.findDraft(owner, organization, draft).isEmpty());
        database.transaction(connection -> {
            update(connection, "UPDATE app_identity SET status = 'ACTIVE' WHERE id = ?", owner);
            update(connection, """
                    UPDATE app_organization_membership SET revoked_at = clock_timestamp(), version = version + 1
                    WHERE organization_id = ? AND identity_id = ?
                    """, organization, owner);
            return null;
        });
        assertTrue(projects.findDraft(owner, organization, draft).isEmpty());
        assertTrue(projects.listDrafts(owner, organization).isEmpty());
    }

    @Test
    void rejectsReservedIdsDuplicateIdsForeignOwnersAndWrongEnvironment() {
        UUID owner = UUID.randomUUID();
        UUID organization = UUID.randomUUID();
        UUID otherOrganization = UUID.randomUUID();
        String draft = UUID.randomUUID().toString();
        database.transaction(connection -> {
            identity(connection, owner);
            organization(connection, organization);
            organization(connection, otherOrganization);
            membership(connection, organization, owner);
            update(connection, INSERT_PROJECT, draft, organization, "SANDBOX", owner);
            return null;
        });
        assertRejected("23514", PILOT, organization, "SANDBOX", owner);
        assertRejected("23505", draft, organization, "SANDBOX", owner);
        assertRejected("23503", UUID.randomUUID().toString(), organization, "SIMULACAO", owner);
        assertRejected("23503", UUID.randomUUID().toString(), otherOrganization, "SANDBOX", owner);
    }

    private void assertRejected(String sqlState, String id, UUID organization, String environment, UUID owner) {
        var error = assertThrows(IllegalStateException.class, () -> database.transaction(connection ->
                update(connection, INSERT_PROJECT, id, organization, environment, owner)));
        assertEquals(sqlState, assertInstanceOf(SQLException.class, error.getCause()).getSQLState());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base.toExternalForm()).resolve(path));
    }

    private void setMembershipDates(UUID organization, UUID owner, OffsetDateTime from, OffsetDateTime until) {
        database.transaction(connection -> update(connection, """
                UPDATE app_organization_membership SET valid_from = ?, valid_until = ?
                WHERE organization_id = ? AND identity_id = ?
                """, from, until, organization, owner));
    }

    private static void identity(Connection connection, UUID id) throws SQLException {
        update(connection, """
                INSERT INTO app_identity (id, email_normalized, display_name, status)
                VALUES (?, ?, 'Pessoa de teste', 'ACTIVE')
                """, id, id + "@example.invalid");
    }

    private static void organization(Connection connection, UUID id) throws SQLException {
        update(connection, """
                INSERT INTO app_organization (id, environment, display_name) VALUES (?, 'SANDBOX', 'Organização de teste')
                """, id);
    }

    private static void membership(Connection connection, UUID organization, UUID identity) throws SQLException {
        update(connection, """
                INSERT INTO app_organization_membership (organization_id, identity_id, role)
                VALUES (?, ?, 'DRAFT_OWNER')
                """, organization, identity);
    }
}
