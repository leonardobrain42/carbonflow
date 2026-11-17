package br.carbonflow.pilot;

import static br.carbonflow.pilot.AccessSecrets.digest;
import static br.carbonflow.pilot.AccessSecrets.passwordHash;

import java.security.MessageDigest;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.Map;

import io.vertx.ext.web.RoutingContext;
import jakarta.enterprise.context.RequestScoped;

@RequestScoped
class PilotAccess {
    static final String COOKIE = "carbonflow_session";
    static final int SESSION_SECONDS = 8 * 60 * 60;
    static final String PREP = "PREP";
    static final String REV = "REV";
    static final String PP = "PP";
    static final String VVB = "VVB";

    record Identity(String id, String organization, String name) {}
    record Scope(String project, String organization, String environment, List<String> roles, @com.fasterxml.jackson.annotation.JsonIgnore String powerProof) {
        boolean has(String role) {
            return roles.contains(role);
        }
    }
    record Session(Identity identity, List<Scope> projects) {}
    private record Credentials(Identity identity, byte[] salt, byte[] hash) {}

    private final PilotDatabase database;
    private final ProjectRepository projects;
    private final PilotAudit audit;
    private final RoutingContext routing;
    private Identity identity;
    private boolean resolved;

    PilotAccess(PilotDatabase database, ProjectRepository projects, PilotAudit audit, RoutingContext routing) {
        this.database = database;
        this.projects = projects;
        this.audit = audit;
        this.routing = routing;
    }

    String login(String id, String secret) {
        if (id == null || secret == null || !id.matches("SIM-[A-Z-]{1,32}") || secret.length() > 128) {
            throw unauthenticated();
        }
        var credentials = database.read(connection -> credentials(connection, id));
        byte[] candidate = passwordHash(secret, credentials == null ? new byte[16] : credentials.salt(), 210_000);
        if (credentials == null || !MessageDigest.isEqual(credentials.hash(), candidate)) {
            throw unauthenticated();
        }

        String token = AccessSecrets.newToken();
        String previous = token();
        database.transaction(connection -> {
            endSession(connection, previous);
            try (var statement = connection.prepareStatement("""
                    INSERT INTO pilot_session VALUES (?, ?, clock_timestamp() + interval '8 hours')
                    """)) {
                statement.setBytes(1, digest(token));
                statement.setString(2, credentials.identity().id());
                statement.executeUpdate();
            }
            return null;
        });
        return token;
    }

    void logout() {
        database.read(connection -> {
            endSession(connection, token());
            return null;
        });
    }

    Session session() {
        var actor = actor();
        if (actor == null) {
            throw unauthenticated();
        }
        return new Session(actor, scopes(actor, null));
    }

    Identity actor() {
        if (!resolved) {
            var token = token();
            identity = token == null ? null : database.read(connection -> {
                try (var statement = connection.prepareStatement("""
                        SELECT i.id, i.organization_id, i.display_name
                        FROM pilot_session s JOIN pilot_identity i ON s.identity_id = i.id
                        WHERE s.token_hash = ? AND s.expires_at > clock_timestamp()
                        """)) {
                    statement.setBytes(1, digest(token));
                    try (var rows = statement.executeQuery()) {
                        return rows.next() ? new Identity(rows.getString(1), rows.getString(2), rows.getString(3)) : null;
                    }
                }
            });
            resolved = true;
        }
        return identity;
    }

    Scope require(String project, String action, String target, String... roles) {
        var actor = actor();
        if (actor == null) {
            throw denied(project, action, target, unauthenticated());
        }
        var permitted = scopes(actor, project);
        if (permitted.isEmpty()) {
            throw denied(project, action, target, invisible());
        }
        var scope = permitted.getFirst();
        if (roles.length != 0 && java.util.Collections.disjoint(scope.roles(), List.of(roles))) {
            throw denied(project, action, target, forbidden());
        }
        return scope;
    }

    PilotProblem denied(String project, String action, String target, PilotProblem problem) {
        var actor = actor();
        audit.record(project, actor == null ? null : actor.id(), action, "NEGADO", target, Map.of("code", problem.code));
        return problem;
    }

    void allowed(Scope scope, String action, String target) {
        audit.record(scope.project(), actor().id(), action, "PERMITIDO", target, Map.of());
    }

    static PilotProblem invisible() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "id", "ARC-E6-01", "Recurso indisponível.");
    }

    static PilotProblem forbidden() {
        return new PilotProblem(403, "ACAO_NEGADA", "action", "ARC-E6-01", "Ação indisponível para esta identidade.");
    }

    private static PilotProblem unauthenticated() {
        return new PilotProblem(401, "SESSAO_NECESSARIA", "session", "ADR-004", "Entre com uma identidade do piloto.");
    }

    private List<Scope> scopes(Identity actor, String project) {
        return projects.pilotScopes(actor, project).stream()
                .map(scope -> new Scope(scope.projectId(), scope.organizationId(), scope.environment(),
                        scope.roles(), scope.powerProof()))
                .toList();
    }

    private static Credentials credentials(Connection connection, String id) throws SQLException {
        try (var statement = connection.prepareStatement("""
                SELECT id, organization_id, display_name, password_salt, password_hash FROM pilot_identity WHERE id = ?
                """)) {
            statement.setString(1, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? new Credentials(new Identity(rows.getString(1), rows.getString(2), rows.getString(3)),
                        rows.getBytes(4), rows.getBytes(5)) : null;
            }
        }
    }

    private String token() {
        var cookie = routing.request().getCookie(COOKIE);
        return cookie != null && AccessSecrets.isToken(cookie.getValue()) ? cookie.getValue() : null;
    }

    private static void endSession(Connection connection, String token) throws SQLException {
        try (var statement = connection.prepareStatement("""
                DELETE FROM pilot_session WHERE expires_at <= clock_timestamp() OR token_hash = ?
                """)) {
            statement.setBytes(1, token == null ? null : digest(token));
            statement.executeUpdate();
        }
    }

}
