package br.carbonflow.pilot;

import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import javax.sql.DataSource;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class RunStore {
    record Entry(Fixture.Reference fixture, String reportJson, String actor) {}

    private final DataSource dataSource;
    private final PilotDatabase database;
    private final PilotAudit audit;

    RunStore(DataSource dataSource, PilotDatabase database, PilotAudit audit) {
        this.dataSource = dataSource;
        this.database = database;
        this.audit = audit;
    }

    Optional<Entry> byKey(PilotAccess.Scope scope, String key) {
        return find(scope, "command_key = ?", key);
    }

    Optional<Entry> byId(PilotAccess.Scope scope, UUID id) {
        return find(scope, "id = ?", id);
    }

    Optional<Entry> latest(PilotAccess.Scope scope) {
        return find(scope, "true ORDER BY created_at DESC, id DESC LIMIT 1");
    }

    Optional<byte[]> snapshot(PilotAccess.Scope scope, UUID id) {
        String sql = "SELECT snapshot FROM synthetic_run WHERE organization_id = ? AND project_id = ? AND environment = ? AND id = ?";
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.organization());
            statement.setString(2, scope.project());
            statement.setString(3, scope.environment());
            statement.setObject(4, id);
            try (var rows = statement.executeQuery()) {
                return rows.next() ? Optional.of(rows.getBytes("snapshot")) : Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar o snapshot", e);
        }
    }

    boolean insert(PilotAccess.Scope scope, String actor, UUID id, String key, Fixture.Reference fixture,
                   OffsetDateTime at, byte[] snapshot, String reportJson) {
        String sql = """
                INSERT INTO synthetic_run (organization_id, project_id, environment, actor_id, id, command_key, fixture_id, fixture_version,
                                           created_at, snapshot, report)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?::jsonb)
                ON CONFLICT (project_id, environment, command_key) DO NOTHING
                """;
        return database.transaction(connection -> {
            boolean inserted = PilotSql.update(connection, sql, scope.organization(), scope.project(), scope.environment(),
                    actor, id, key, fixture.id(), fixture.version(), at, snapshot, reportJson) == 1;
            if (inserted) {
                audit.record(connection, scope.project(), actor, "CALCULAR", "PERMITIDO", id.toString(),
                        Map.of("fixture", fixture));
            }
            return inserted;
        });
    }

    private Optional<Entry> find(PilotAccess.Scope scope, String filter, Object... arguments) {
        String sql = "SELECT fixture_id, fixture_version, report, actor_id FROM synthetic_run WHERE organization_id = ? AND project_id = ? AND environment = ? AND "
                + filter;
        try (var connection = dataSource.getConnection(); var statement = connection.prepareStatement(sql)) {
            statement.setString(1, scope.organization());
            statement.setString(2, scope.project());
            statement.setString(3, scope.environment());
            for (int i = 0; i < arguments.length; i++) {
                statement.setObject(i + 4, arguments[i]);
            }
            try (var rows = statement.executeQuery()) {
                if (rows.next()) {
                    var fixture = new Fixture.Reference(rows.getString("fixture_id"), rows.getInt("fixture_version"));
                    return Optional.of(new Entry(fixture, rows.getString("report"), rows.getString("actor_id")));
                }
                return Optional.empty();
            }
        } catch (SQLException e) {
            throw new IllegalStateException("Falha ao consultar execuções", e);
        }
    }
}
