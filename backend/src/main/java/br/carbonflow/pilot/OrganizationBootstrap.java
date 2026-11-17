package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class OrganizationBootstrap {
    private static final String ENVIRONMENT = "SANDBOX";
    private static final String STATUS = "PRIVATE_DRAFT";
    private static final String ROLE = "DRAFT_OWNER";
    private static final String ACTIVE_WORKSPACE = """
            SELECT o.display_name, o.environment, o.status, o.version, m.role
            FROM app_organization o
            JOIN app_organization_membership m ON m.organization_id = o.id
            WHERE o.id = ? AND m.identity_id = ? AND o.environment = 'SANDBOX' AND m.role = 'DRAFT_OWNER'
              AND m.revoked_at IS NULL AND m.valid_from <= statement_timestamp()
              AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
            """;

    record Workspace(UUID id, String name, String environment, String status, int version, String role) {}
    private record Bootstrap(UUID organizationId, String key, byte[] fingerprint) {}

    private final PilotDatabase database;
    private final AccountAccess accounts;

    OrganizationBootstrap(PilotDatabase database, AccountAccess accounts) {
        this.database = database;
        this.accounts = accounts;
    }

    Workspace create(String token, String displayName, String commandKey) {
        return database.transaction(connection -> {
            var identity = accounts.lockIdentity(connection, token);
            String name = normalizedName(displayName);
            String key = PilotCommands.key(commandKey);
            byte[] fingerprint = AccessSecrets.digest(name);
            var previous = first(connection, """
                    SELECT organization_id, command_key, request_fingerprint FROM app_organization_bootstrap
                    WHERE identity_id = ?
                    """, row -> new Bootstrap(row.getObject("organization_id", UUID.class),
                    row.getString("command_key"), row.getBytes("request_fingerprint")), identity.id());
            if (previous != null) {
                return replay(connection, identity.id(), name, key, fingerprint, previous);
            }

            UUID organizationId = UUID.randomUUID();
            update(connection, """
                    INSERT INTO app_organization (id, environment, display_name, status) VALUES (?, ?, ?, ?)
                    """, organizationId, ENVIRONMENT, name, STATUS);
            update(connection, """
                    INSERT INTO app_organization_membership (organization_id, identity_id, role) VALUES (?, ?, ?)
                    """, organizationId, identity.id(), ROLE);
            update(connection, """
                    INSERT INTO app_organization_bootstrap (identity_id, organization_id, event_id,
                        command_key, request_fingerprint) VALUES (?, ?, ?, ?, ?)
                    """, identity.id(), organizationId, UUID.randomUUID(), key, fingerprint);
            return initialWorkspace(organizationId, name);
        });
    }

    Workspace access(String token, UUID organizationId) {
        return database.read(connection -> {
            var identity = accounts.requireIdentity(connection, token);
            return requireWorkspace(connection, ACTIVE_WORKSPACE, organizationId, identity.id());
        });
    }

    private static Workspace replay(Connection connection, UUID identityId, String name, String key,
                                    byte[] fingerprint, Bootstrap previous) throws SQLException {
        requireWorkspace(connection, ACTIVE_WORKSPACE + "FOR SHARE OF m", previous.organizationId(), identityId);
        if (!previous.key().equals(key)) {
            throw new PilotProblem(409, "ORGANIZACAO_INICIAL_EXISTENTE", "organization", "CF-IMP-004",
                    "A conta já criou seu espaço inicial.");
        }
        if (!MessageDigest.isEqual(previous.fingerprint(), fingerprint)) {
            throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "CF-IMP-004",
                    "A chave já identifica outro comando.");
        }
        return initialWorkspace(previous.organizationId(), name);
    }

    private static Workspace requireWorkspace(Connection connection, String sql, UUID organizationId,
                                              UUID identityId) throws SQLException {
        var workspace = first(connection, sql, row -> new Workspace(organizationId,
                row.getString("display_name"), row.getString("environment"), row.getString("status"),
                row.getInt("version"), row.getString("role")), organizationId, identityId);
        if (workspace == null) {
            throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "organization", "CF-IMP-004", "Recurso indisponível.");
        }
        return workspace;
    }

    private static Workspace initialWorkspace(UUID id, String name) {
        return new Workspace(id, name, ENVIRONMENT, STATUS, 1, ROLE);
    }

    private static String normalizedName(String value) {
        String name = value == null ? "" : value.strip();
        if (name.isEmpty() || name.length() > 120 || name.codePoints().anyMatch(Character::isISOControl)) {
            throw new PilotProblem(400, "PEDIDO_INVALIDO", "name", "CF-IMP-004", "Informe um nome de trabalho válido.");
        }
        return name;
    }
}
