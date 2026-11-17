package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectDrafts {
    record Metadata(String projectId, UUID organizationId, String environment, String state,
                    int version, String title, String description) {}
    private record CommandReceipt(String action, String projectId, byte[] fingerprint, String response) {}

    private static final String VISIBLE = """
            SELECT p.id, p.organization_id, p.environment, p.state, p.version, p.title, p.description
            FROM app_project p JOIN app_organization_membership m
              ON m.organization_id = p.organization_id AND m.identity_id = p.created_by
            WHERE p.id = ? AND p.created_by = ? AND m.identity_id = ?
              AND p.environment = 'SANDBOX' AND p.state = 'DRAFT'
              AND m.role = 'DRAFT_OWNER' AND m.revoked_at IS NULL
              AND m.valid_from <= statement_timestamp()
              AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
            """;

    private final PilotDatabase database;
    private final AccountAccess accounts;
    private final ObjectMapper mapper;

    ProjectDrafts(PilotDatabase database, AccountAccess accounts, ObjectMapper mapper) {
        this.database = database;
        this.accounts = accounts;
        this.mapper = mapper;
    }

    Metadata metadata(String token, String project) {
        return database.read(connection -> {
            var actor = accounts.requireIdentity(connection, token);
            return requireVisible(connection, project, actor.id(), false);
        });
    }

    JsonNode create(String token, String keyHeader, ProjectDraftResource.Creation input) {
        return database.transaction(connection -> {
            var actor = accounts.lockIdentity(connection, token);
            String key = PilotCommands.key(keyHeader);
            UUID organization = input == null ? null : input.organizationId();
            if (organization == null) throw invalid("organizationId");
            String title = title(input.title());
            String description = description(input.description());
            ObjectNode request = mapper.createObjectNode();
            request.put("organizationId", organization.toString());
            request.put("title", title);
            if (description == null) request.putNull("description"); else request.put("description", description);
            byte[] fingerprint = AccessSecrets.digest(request.toString());

            serialiseKey(connection, actor.id(), key);
            var previous = receipt(connection, actor.id(), key);
            if (previous != null) return replay(connection, actor.id(), previous, "CREATE", null, fingerprint);

            requireOrganization(connection, actor.id(), organization);
            String projectId = UUID.randomUUID().toString();
            update(connection, """
                    INSERT INTO app_project (id, organization_id, environment, title, description, created_by)
                    VALUES (?, ?, 'SANDBOX', ?, ?, ?)
                    """, projectId, organization, title, description, actor.id());
            Metadata project = requireVisible(connection, projectId, actor.id(), false);
            JsonNode result = mapper.valueToTree(project);
            event(connection, projectId, actor.id(), "CREATED", project.version());
            remember(connection, actor.id(), key, "CREATE", projectId, fingerprint, result);
            return result;
        });
    }

    JsonNode update(String token, String projectId, String keyHeader, String ifMatch, JsonNode patch) {
        return database.transaction(connection -> {
            var actor = accounts.lockIdentity(connection, token);
            String key = PilotCommands.key(keyHeader);
            int expected = PilotCommands.expected(ifMatch);
            if (patch == null || !patch.isObject() || patch.isEmpty()) throw invalid("body");
            patch.fieldNames().forEachRemaining(field -> {
                if (!"title".equals(field) && !"description".equals(field)) throw invalid(field);
            });
            ObjectNode normalized = mapper.createObjectNode();
            normalized.put("projectId", projectId);
            normalized.put("expected", expected);
            if (patch.has("title")) normalized.put("title", title(text(patch.get("title"), "title")));
            if (patch.has("description")) {
                String desc = patch.get("description").isNull() ? null : description(text(patch.get("description"), "description"));
                if (desc == null) normalized.putNull("description"); else normalized.put("description", desc);
            }
            byte[] fingerprint = AccessSecrets.digest(normalized.toString());

            serialiseKey(connection, actor.id(), key);
            var previous = receipt(connection, actor.id(), key);
            if (previous != null) return replay(connection, actor.id(), previous, "PATCH", projectId, fingerprint);
            var current = requireVisible(connection, projectId, actor.id(), true);
            PilotCommands.requireVersion(expected, current.version());
            String nextTitle = normalized.has("title") ? normalized.get("title").asText() : current.title();
            String nextDescription = normalized.has("description")
                    ? (normalized.get("description").isNull() ? null : normalized.get("description").asText())
                    : current.description();
            update(connection, """
                    UPDATE app_project SET title = ?, description = ?, version = version + 1,
                        updated_at = statement_timestamp() WHERE id = ?
                    """, nextTitle, nextDescription, projectId);
            Metadata changed = requireVisible(connection, projectId, actor.id(), false);
            JsonNode result = mapper.valueToTree(changed);
            event(connection, projectId, actor.id(), "METADATA_UPDATED", changed.version());
            remember(connection, actor.id(), key, "PATCH", projectId, fingerprint, result);
            return result;
        });
    }

    private static Metadata requireVisible(Connection connection, String projectId, UUID actor, boolean lock)
            throws SQLException {
        String sql = VISIBLE + (lock ? "FOR UPDATE OF p FOR SHARE OF m" : "");
        Metadata item = first(connection, sql, row -> new Metadata(row.getString("id"),
                row.getObject("organization_id", UUID.class), row.getString("environment"),
                row.getString("state"), row.getInt("version"), row.getString("title"),
                row.getString("description")), projectId, actor, actor);
        if (item == null) throw invisible();
        return item;
    }

    private static void requireOrganization(Connection connection, UUID actor, UUID organization) throws SQLException {
        Boolean allowed = first(connection, """
                SELECT true FROM app_organization o JOIN app_organization_membership m
                  ON m.organization_id = o.id
                WHERE o.id = ? AND m.identity_id = ? AND o.environment = 'SANDBOX'
                  AND o.status = 'PRIVATE_DRAFT' AND m.role = 'DRAFT_OWNER'
                  AND m.revoked_at IS NULL AND m.valid_from <= statement_timestamp()
                  AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
                FOR SHARE OF o, m
                """, row -> true, organization, actor);
        if (allowed == null) throw invisible();
    }

    private static void serialiseKey(Connection connection, UUID actor, String key) throws SQLException {
        first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> true,
                "app_project_command:" + actor + ":" + key);
    }

    private static CommandReceipt receipt(Connection connection, UUID actor, String key) throws SQLException {
        return first(connection, """
                SELECT action, project_id, request_fingerprint, response::text AS response
                FROM app_project_command WHERE identity_id = ? AND command_key = ?
                """, row -> new CommandReceipt(row.getString("action"), row.getString("project_id"),
                row.getBytes("request_fingerprint"), row.getString("response")), actor, key);
    }

    private JsonNode replay(Connection connection, UUID actor, CommandReceipt receipt, String action,
                            String projectId, byte[] fingerprint) throws SQLException {
        if (!action.equals(receipt.action()) || (projectId != null && !projectId.equals(receipt.projectId()))
                || !MessageDigest.isEqual(fingerprint, receipt.fingerprint())) {
            throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "CF-IMP-007",
                    "A chave já identifica outro comando.");
        }
        requireVisible(connection, receipt.projectId(), actor, false);
        try {
            return mapper.readTree(receipt.response());
        } catch (JsonProcessingException error) {
            throw new IllegalStateException("Comprovante de comando inválido", error);
        }
    }

    private static void remember(Connection connection, UUID actor, String key, String action, String projectId,
                                 byte[] fingerprint, JsonNode response) throws SQLException {
        update(connection, """
                INSERT INTO app_project_command
                  (identity_id, command_key, action, project_id, request_fingerprint, response)
                VALUES (?, ?, ?, ?, ?, ?::jsonb)
                """, actor, key, action, projectId, fingerprint, response.toString());
    }

    private static void event(Connection connection, String projectId, UUID actor, String action, int version)
            throws SQLException {
        update(connection, """
                INSERT INTO app_project_event (id, project_id, identity_id, action, version)
                VALUES (?, ?, ?, ?, ?)
                """, UUID.randomUUID(), projectId, actor, action, version);
    }

    private static String text(JsonNode value, String field) {
        if (value == null || !value.isTextual()) throw invalid(field);
        return value.asText();
    }

    private static String title(String input) {
        String normalized = input == null ? "" : input.strip();
        if (normalized.isEmpty() || normalized.length() > 160 || normalized.codePoints().anyMatch(Character::isISOControl))
            throw invalid("title");
        return normalized;
    }

    private static String description(String input) {
        if (input == null || input.isBlank()) return null;
        String normalized = input.strip();
        if (normalized.length() > 2000 || normalized.codePoints().anyMatch(code ->
                Character.isISOControl(code) && code != '\n' && code != '\r' && code != '\t'))
            throw invalid("description");
        return normalized;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-007",
                "Revise os metadados do projeto.");
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "project", "CF-IMP-007",
                "Projeto indisponível.");
    }
}
