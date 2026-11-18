package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

/** Applicant-side protocol for an authority review. Decisions belong to the compliance service. */
@ApplicationScoped
class ProjectAuthorityRequests {
    enum Power { REPRESENT_PROJECT, SEND_INVITATIONS }
    enum State { SUBMITTED, WITHDRAWN }
    record Input(Power power, String representationBasis, String evidenceReference) {}
    record Entry(UUID id, int version, State state, Input details, OffsetDateTime recordedAt) {}
    record View(String projectId, UUID organizationId, String environment, List<Entry> requests) {}

    private static final String COLUMNS = """
            request_id, version, requested_power, state, representation_basis, evidence_reference, recorded_at
            """;
    private final DynamicProjectScope scope;

    ProjectAuthorityRequests(DynamicProjectScope scope) {
        this.scope = scope;
    }

    View get(String token, String project) {
        return scope.read(token, project, "VIEW_AUTHORITY_REQUESTS", ProjectAuthorityRequests::view);
    }

    View submit(String token, String project, UUID id, Input input) {
        return scope.write(token, project, "SUBMIT_AUTHORITY_REQUEST", (connection, context) -> {
            Input normalized = normalize(input);
            View previous = view(connection, context);
            Entry sameId = previous.requests().stream().filter(entry -> entry.id().equals(id)).findFirst().orElse(null);
            if (sameId != null) {
                if (sameId.details().equals(normalized)) return previous;
                throw new PilotProblem(409, "PROTOCOLO_REUSADO", "request", "CF-IMP-028",
                        "Este protocolo já foi registrado com outros dados.");
            }
            if (previous.requests().stream().anyMatch(entry -> entry.state() == State.SUBMITTED
                    && entry.details().power() == normalized.power())) {
                throw new PilotProblem(409, "SOLICITACAO_EM_ABERTO", "power", "CF-IMP-028",
                        "Já existe uma solicitação em aberto para esse poder.");
            }
            insert(connection, context, id, 1, State.SUBMITTED, normalized);
            return view(connection, context);
        });
    }

    View withdraw(String token, String project, UUID id, String ifMatch) {
        return scope.write(token, project, "WITHDRAW_AUTHORITY_REQUEST", (connection, context) -> {
            int expected = PilotCommands.expected(ifMatch);
            Entry current = first(connection, "SELECT " + COLUMNS + """
                    FROM app_project_authority_request_revision WHERE project_id = ? AND request_id = ?
                    ORDER BY version DESC LIMIT 1
                    """, ProjectAuthorityRequests::entry, project, id);
            if (current == null) throw new PilotProblem(404, "RECURSO_NAO_VISIVEL", "request", "CF-IMP-028",
                    "Protocolo indisponível.");
            if (current.state() == State.WITHDRAWN && expected >= 1 && expected <= current.version()) {
                return view(connection, context);
            }
            PilotCommands.requireVersion(expected, current.version());
            insert(connection, context, id, current.version() + 1, State.WITHDRAWN, current.details());
            return view(connection, context);
        });
    }

    private static View view(Connection connection, DynamicProjectScope.Context context) throws SQLException {
        var entries = query(connection, "SELECT DISTINCT ON (request_id) " + COLUMNS + """
                FROM app_project_authority_request_revision WHERE project_id = ?
                ORDER BY request_id, version DESC
                """, ProjectAuthorityRequests::entry, context.id());
        return new View(context.id(), context.organization(), context.environment(), entries);
    }

    private static Entry entry(ResultSet row) throws SQLException {
        return new Entry(row.getObject("request_id", UUID.class), row.getInt("version"),
                State.valueOf(row.getString("state")),
                new Input(Power.valueOf(row.getString("requested_power")), row.getString("representation_basis"),
                        row.getString("evidence_reference")),
                row.getObject("recorded_at", OffsetDateTime.class));
    }

    private static void insert(Connection connection, DynamicProjectScope.Context context, UUID id,
                               int version, State state, Input input) throws SQLException {
        update(connection, """
                INSERT INTO app_project_authority_request_revision
                  (project_id, organization_id, environment, request_id, version, actor_id, requested_power,
                   representation_basis, evidence_reference, state)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """, context.id(), context.organization(), context.environment(), id, version, context.actor().id(),
                input.power().name(), input.representationBasis(), input.evidenceReference(), state.name());
    }

    private static Input normalize(Input input) {
        if (input == null || input.power() == null) throw invalid("power");
        return new Input(input.power(), text(input.representationBasis(), "representationBasis", 400),
                text(input.evidenceReference(), "evidenceReference", 240));
    }

    private static String text(String value, String field, int max) {
        if (value == null || value.isBlank()) throw invalid(field);
        String cleaned = value.strip();
        if (cleaned.codePointCount(0, cleaned.length()) > max
                || cleaned.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) throw invalid(field);
        return cleaned;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-028",
                "Informe o poder solicitado, o fundamento da representação e a referência do documento.");
    }
}
