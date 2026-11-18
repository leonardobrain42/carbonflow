package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectInvitationPlans {
    enum Role { PREP, REV }
    enum Activity { INTAKE, EVIDENCE, BASELINE, ADDITIONALITY, CALCULATION, MONITORING, DOCUMENTATION }
    enum DeclaredConflict { UNKNOWN, NONE_DECLARED, DECLARED }
    enum Independence { REVIEW_REQUIRED, NONE_DECLARED, DECLARED_CONFLICT, SAME_PREPARER_AND_REVIEWER }
    record Input(String email, Role role, List<Activity> activities, LocalDate deadline, String competence,
                 DeclaredConflict declaredConflict, String conflictNote) {}
    record Plan(UUID id, int version, Input details, String state, Independence independence) {}
    record View(String projectId, String environment, List<Plan> plans) {}
    private record Revision(UUID id, int version, Input details) {}

    private static final String COLUMNS = """
            plan_id, version, email_normalized, proposed_role, activities, deadline, competence,
            declared_conflict, conflict_note
            """;

    private final DynamicProjectScope scopes;

    ProjectInvitationPlans(DynamicProjectScope scopes) {
        this.scopes = scopes;
    }

    View get(String token, String project) {
        return scopes.read(token, project, "VIEW_INVITATION_PLANS", ProjectInvitationPlans::view);
    }

    View save(String token, String project, UUID id, String ifMatch, Input submitted) {
        return scopes.write(token, project, "SAVE_INVITATION_PLAN", (connection, context) -> {
            int expected = PilotCommands.expected(ifMatch);
            Input input = normalize(submitted);
            Revision current = first(connection, "SELECT " + COLUMNS + """
                    FROM app_project_invitation_plan_revision WHERE project_id = ? AND plan_id = ?
                    ORDER BY version DESC LIMIT 1
                    """, ProjectInvitationPlans::revision, project, id);
            int version = current == null ? 0 : current.version();
            if (current != null && expected == version - 1 && input.equals(current.details())) {
                return view(connection, context);
            }
            PilotCommands.requireVersion(expected, version);
            if (current != null && input.equals(current.details())) return view(connection, context);

            String[] activities = input.activities().stream().map(Activity::name).toArray(String[]::new);
            var entries = connection.createArrayOf("text", activities);
            try {
                update(connection, """
                        INSERT INTO app_project_invitation_plan_revision
                          (project_id, organization_id, environment, plan_id, version, actor_id,
                           email_normalized, proposed_role, activities, deadline, competence, declared_conflict, conflict_note)
                        VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                        """, context.id(), context.organization(), context.environment(), id, version + 1, context.actor().id(),
                        input.email(), input.role().name(), entries, input.deadline(), input.competence(),
                        input.declaredConflict().name(), input.conflictNote());
            } finally {
                entries.free();
            }
            return view(connection, context);
        });
    }

    private static View view(Connection connection, DynamicProjectScope.Context context) throws SQLException {
        var current = query(connection, "SELECT DISTINCT ON (plan_id) " + COLUMNS + """
                FROM app_project_invitation_plan_revision WHERE project_id = ?
                ORDER BY plan_id, version DESC
                """, ProjectInvitationPlans::revision, context.id());
        var rolesByEmail = new HashMap<String, Set<Role>>();
        for (Revision revision : current) {
            rolesByEmail.computeIfAbsent(revision.details().email(), ignored -> java.util.EnumSet.noneOf(Role.class))
                    .add(revision.details().role());
        }
        var plans = current.stream().map(revision -> {
            Input input = revision.details();
            Independence independence = rolesByEmail.get(input.email()).size() > 1
                    ? Independence.SAME_PREPARER_AND_REVIEWER
                    : switch (input.declaredConflict()) {
                        case DECLARED -> Independence.DECLARED_CONFLICT;
                        case NONE_DECLARED -> Independence.NONE_DECLARED;
                        case UNKNOWN -> Independence.REVIEW_REQUIRED;
                    };
            return new Plan(revision.id(), revision.version(), input, "PLANNED", independence);
        }).toList();
        return new View(context.id(), context.environment(), plans);
    }

    private static Revision revision(ResultSet row) throws SQLException {
        var entries = row.getArray("activities");
        try {
            var activities = java.util.Arrays.stream((String[]) entries.getArray()).map(Activity::valueOf).toList();
            return new Revision(row.getObject("plan_id", UUID.class), row.getInt("version"),
                    new Input(row.getString("email_normalized"), Role.valueOf(row.getString("proposed_role")), activities,
                            row.getObject("deadline", LocalDate.class), row.getString("competence"),
                            DeclaredConflict.valueOf(row.getString("declared_conflict")), row.getString("conflict_note")));
        } finally {
            entries.free();
        }
    }

    private static Input normalize(Input input) {
        if (input == null || input.role() == null) throw invalid("role");
        if (input.activities() == null || input.activities().isEmpty() || input.activities().size() > Activity.values().length
                || input.activities().stream().anyMatch(java.util.Objects::isNull)) throw invalid("activities");
        String competence = text(input.competence(), "competence");
        String note = text(input.conflictNote(), "conflictNote");
        DeclaredConflict conflict = input.declaredConflict() == null ? DeclaredConflict.UNKNOWN : input.declaredConflict();
        if (conflict == DeclaredConflict.DECLARED && note == null) throw invalid("conflictNote");
        return new Input(AccountAccess.email(input.email()), input.role(), input.activities().stream().distinct().sorted().toList(),
                input.deadline(), competence, conflict, note);
    }

    private static String text(String value, String field) {
        if (value == null || value.isBlank()) return null;
        String normalized = value.strip();
        if (normalized.codePointCount(0, normalized.length()) > 400
                || normalized.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw invalid(field);
        }
        return normalized;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-012",
                "Confira o papel, as atividades e a declaração de conflito do plano.");
    }
}
