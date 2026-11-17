package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Boundary between account projects and the legacy simulated E8 pilot.
 * A draft owner can prepare inputs; it NEVER becomes PREP, PP, REV or VVB.
 * Every dynamic technical operation must pass through this tenant-scoped gate.
 */
@ApplicationScoped
class DynamicProjectScope {
    record Context(AccountAccess.Identity actor, ProjectContext.Draft project) {
        String id() { return project.projectId(); }
        UUID organization() { return project.organizationId(); }
        String environment() { return project.environment(); }
    }

    @FunctionalInterface
    interface Work<T> { T execute(Connection connection, Context context) throws SQLException; }

    private static final String SCOPE_SQL = """
            SELECT p.id, p.organization_id, p.environment, p.state, p.version
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

    DynamicProjectScope(PilotDatabase database, AccountAccess accounts) {
        this.database = database;
        this.accounts = accounts;
    }

    <T> T read(String cookie, String project, String action, Work<T> body) {
        try {
            return database.read(connection -> {
                var actor = accounts.requireIdentity(connection, cookie);
                var scope = require(connection, actor, project, false);
                T result = body.execute(connection, scope);
                audit(connection, scope.actor().id(), project, action, "PERMITTED", "OK");
                return result;
            });
        } catch (PilotProblem problem) {
            if (problem.status == 404) denied(cookie, project, action);
            throw problem;
        }
    }

    <T> T write(String cookie, String project, String action, Work<T> body) {
        try {
            return database.transaction(connection -> {
                var actor = accounts.lockIdentity(connection, cookie);
                var scope = require(connection, actor, project, true);
                T result = body.execute(connection, scope);
                audit(connection, scope.actor().id(), project, action, "PERMITTED", "OK");
                return result;
            });
        } catch (PilotProblem problem) {
            if (problem.status == 404) denied(cookie, project, action);
            throw problem;
        }
    }

    private static Context require(Connection connection, AccountAccess.Identity actor, String project,
                                   boolean lock) throws SQLException {
        var draft = first(connection, SCOPE_SQL + (lock ? "FOR UPDATE OF p FOR SHARE OF m" : ""),
                row -> new ProjectContext.Draft(row.getString("id"), row.getObject("organization_id", UUID.class),
                        row.getString("environment"), row.getString("state"), row.getInt("version")),
                project, actor.id(), actor.id());
        if (draft == null) {
            throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "project", "CF-IMP-008",
                    "Recurso indisponível.");
        }
        return new Context(actor, draft);
    }

    private void denied(String cookie, String project, String action) {
        try {
            database.transaction(connection -> {
                var actor = accounts.requireIdentity(connection, cookie);
                audit(connection, actor.id(), project, action, "DENIED", "RECURSO_NAO_VISIVEL");
                return null;
            });
        } catch (PilotProblem problem) {
            if (problem.status != 401) throw problem;
            // No authenticated actor => no account-scoped audit entry.
        }
    }

    private static void audit(Connection connection, UUID actor, String target, String action,
                              String outcome, String code) throws SQLException {
        update(connection, """
                INSERT INTO app_project_technical_access_event
                  (id, actor_id, target_digest, action, outcome, code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor, AccessSecrets.digest(target), action, outcome, code);
    }
}
