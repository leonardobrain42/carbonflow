package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Boundary between account projects and the legacy simulated E8 pilot.
 * A draft owner can prepare inputs; it NEVER becomes PREP, PP, REV or VVB.
 * Every dynamic technical operation must pass through this tenant-scoped gate.
 */
@ApplicationScoped
class DynamicProjectScope {
    record Context(AccountAccess.Identity actor, ProjectContext.Draft project, boolean owner, String role, Set<String> actions) {
        String id() { return project.projectId(); }
        UUID organization() { return project.organizationId(); }
        String environment() { return project.environment(); }
    }

    @FunctionalInterface
    interface Work<T> { T execute(Connection connection, Context context) throws SQLException; }

    private record Target(ProjectContext.Draft project, UUID creator) {}

    private static final String PROJECT = """
            SELECT id, organization_id, environment, state, version, created_by
            FROM app_project WHERE id = ? AND environment = 'SANDBOX' AND state = 'DRAFT'
            """;

    private final PilotDatabase database;
    private final AccountAccess accounts;

    DynamicProjectScope(PilotDatabase database, AccountAccess accounts) {
        this.database = database;
        this.accounts = accounts;
    }

    <T> T read(String cookie, String project, String action, Work<T> body) {
        try {
            return database.transaction(connection -> {
                var actor = accounts.requireIdentity(connection, cookie);
                var scope = require(connection, actor, project, action, false);
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
                var scope = require(connection, actor, project, action, true);
                T result = body.execute(connection, scope);
                audit(connection, scope.actor().id(), project, action, "PERMITTED", "OK");
                return result;
            });
        } catch (PilotProblem problem) {
            if (problem.status == 404) denied(cookie, project, action);
            throw problem;
        }
    }

    private static Context require(Connection connection, AccountAccess.Identity actor, String project, String action,
                                   boolean write) throws SQLException {
        Target target = first(connection, PROJECT + (write ? "FOR UPDATE" : "FOR SHARE"), row -> new Target(
                new ProjectContext.Draft(row.getString("id"), row.getObject("organization_id", UUID.class),
                        row.getString("environment"), row.getString("state"), row.getInt("version"), null),
                row.getObject("created_by", UUID.class)), project);
        if (target != null) {
            Boolean owns = target.creator().equals(actor.id()) ? first(connection, """
                    SELECT true FROM app_organization_membership WHERE organization_id = ? AND identity_id = ?
                    AND role = 'DRAFT_OWNER' AND revoked_at IS NULL AND valid_from <= statement_timestamp()
                    AND (valid_until IS NULL OR valid_until > statement_timestamp()) FOR SHARE
                    """, row -> true, target.project().organizationId(), actor.id()) : null;
            if (Boolean.TRUE.equals(owns)) {
                return new Context(actor, target.project(), true, "DRAFT_OWNER", ProjectCollaboration.OWNER_TECHNICAL_ACTIONS);
            }
            var access = ProjectCollaboration.access(connection, project, actor.id());
            if (access.actions().contains(action)) return new Context(actor, target.project(), false, access.role(), access.actions());
        }
        throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "project", "CF-IMP-008", "Recurso indisponível.");
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

    static void audit(Connection connection, UUID actor, String target, String action,
                              String outcome, String code) throws SQLException {
        update(connection, """
                INSERT INTO app_project_technical_access_event
                  (id, actor_id, target_digest, action, outcome, code)
                VALUES (?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), actor, AccessSecrets.digest(target), action, outcome, code);
    }
}
