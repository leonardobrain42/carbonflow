package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

@ApplicationScoped
class PilotAudit {
    record Event(UUID id, String actor, String action, String outcome, String target,
                 java.time.OffsetDateTime occurredAt, JsonNode details) {}

    private final PilotDatabase database;
    private final ObjectMapper mapper;

    PilotAudit(PilotDatabase database, ObjectMapper mapper) {
        this.database = database;
        this.mapper = mapper;
    }

    UUID record(Connection connection, String project, String actor, String action, String outcome,
                String target, Object details) throws SQLException {
        UUID id = UUID.randomUUID();
        String sql = """
                INSERT INTO pilot_access_event (id, project_id, actor_id, action, outcome, target, occurred_at, details)
                VALUES (?, ?, ?, ?, ?, ?, clock_timestamp(), ?::jsonb)
                """;
        try (var statement = connection.prepareStatement(sql)) {
            statement.setObject(1, id);
            statement.setString(2, project);
            statement.setString(3, actor);
            statement.setString(4, action);
            statement.setString(5, outcome);
            statement.setString(6, target);
            statement.setString(7, encode(details));
            statement.executeUpdate();
        }
        return id;
    }

    void record(String project, String actor, String action, String outcome, String target, Object details) {
        database.read(connection -> record(connection, project, actor, action, outcome, target, details));
    }

    List<Event> events(PilotAccess.Scope scope, int limit) {
        String sql = """
                SELECT e.id, CASE WHEN i.organization_id = ? THEN e.actor_id END AS actor,
                       e.action, e.outcome,
                       CASE WHEN i.organization_id = ? THEN e.target END AS target,
                       e.occurred_at,
                       CASE WHEN i.organization_id = ? THEN e.details ELSE '{}'::jsonb END AS details
                FROM pilot_access_event e LEFT JOIN pilot_identity i ON i.id = e.actor_id
                WHERE e.project_id = ?
                ORDER BY e.occurred_at DESC, e.id DESC LIMIT ?
                """;
        return database.read(connection -> {
            var events = new ArrayList<Event>();
            try (var statement = connection.prepareStatement(sql)) {
                statement.setString(1, scope.organization());
                statement.setString(2, scope.organization());
                statement.setString(3, scope.organization());
                statement.setString(4, scope.project());
                statement.setInt(5, limit);
                try (var rows = statement.executeQuery()) {
                    while (rows.next()) {
                        events.add(new Event(rows.getObject("id", UUID.class), rows.getString("actor"),
                                rows.getString("action"), rows.getString("outcome"), rows.getString("target"),
                                rows.getObject("occurred_at", java.time.OffsetDateTime.class),
                                decode(rows.getString("details"))));
                    }
                }
            }
            return events;
        });
    }

    String encode(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao serializar trilha do piloto", e);
        }
    }

    JsonNode decode(String value) {
        try {
            return mapper.readTree(value);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("Falha ao ler trilha do piloto", e);
        }
    }
}
