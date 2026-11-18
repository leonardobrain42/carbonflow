package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectExperience {
    enum Mode { GUIDED, TECHNICAL }
    record Responsibilities(String pp, String prep, String rev, String op, String vvb) {}
    record Preferences(Mode mode, String declaredProfile, String declaredCompetence, Responsibilities responsibilities) {}
    record View(String projectId, String environment, int version, Preferences preferences) {}
    private record Revision(int version, Preferences preferences) {}

    private static final Set<String> PROFILES = Set.of("PROPONENT", "TECHNICAL", "OPERATIONS", "OTHER");
    private static final Responsibilities EMPTY_MAP = new Responsibilities(null, null, null, null, null);
    private static final Revision DEFAULT = new Revision(0, new Preferences(Mode.GUIDED, null, null, EMPTY_MAP));
    private static final String SELECT = """
            SELECT version, mode, declared_profile, declared_competence, proposed_pp, proposed_prep,
                   proposed_rev, proposed_op, proposed_vvb
            FROM app_project_experience_revision WHERE project_id = ?
            """;

    private final DynamicProjectScope scope;

    ProjectExperience(DynamicProjectScope scope) {
        this.scope = scope;
    }

    View get(String token, String project) {
        return scope.read(token, project, "VIEW_EXPERIENCE", (connection, actor) -> view(actor, latest(connection, project)));
    }

    View save(String token, String project, String ifMatch, Preferences input) {
        return scope.write(token, project, "SAVE_EXPERIENCE", (connection, actor) -> {
            int expected = PilotCommands.expected(ifMatch);
            Preferences preferences = normalize(input);
            Revision current = latest(connection, project);
            if (expected < current.version()) {
                // A response lost after commit can be recovered by repeating the same replacement.
                Revision saved = find(connection, project, expected + 1);
                if (saved != null && saved.preferences().equals(preferences)) return view(actor, saved);
            }
            PilotCommands.requireVersion(expected, current.version());
            if (current.preferences().equals(preferences)) return view(actor, current);

            int version = current.version() + 1;
            var roles = preferences.responsibilities();
            update(connection, """
                    INSERT INTO app_project_experience_revision
                      (project_id, organization_id, environment, version, actor_id, mode, declared_profile,
                       declared_competence, proposed_pp, proposed_prep, proposed_rev, proposed_op, proposed_vvb)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                    """, actor.id(), actor.organization(), actor.environment(), version, actor.actor().id(),
                    preferences.mode().name(), preferences.declaredProfile(), preferences.declaredCompetence(),
                    roles.pp(), roles.prep(), roles.rev(), roles.op(), roles.vvb());
            return view(actor, new Revision(version, preferences));
        });
    }

    static Mode mode(Connection connection, String project) throws SQLException {
        Mode mode = first(connection, """
                SELECT mode FROM app_project_experience_revision WHERE project_id = ?
                ORDER BY version DESC LIMIT 1
                """, row -> Mode.valueOf(row.getString(1)), project);
        return mode == null ? Mode.GUIDED : mode;
    }

    private static Revision latest(Connection connection, String project) throws SQLException {
        Revision row = read(connection, SELECT + " ORDER BY version DESC LIMIT 1", project);
        return row == null ? DEFAULT : row;
    }

    private static Revision find(Connection connection, String project, int version) throws SQLException {
        return read(connection, SELECT + " AND version = ?", project, version);
    }

    private static Revision read(Connection connection, String sql, Object... args) throws SQLException {
        return first(connection, sql, row -> new Revision(row.getInt("version"), new Preferences(
                Mode.valueOf(row.getString("mode")), row.getString("declared_profile"),
                row.getString("declared_competence"), new Responsibilities(row.getString("proposed_pp"),
                row.getString("proposed_prep"), row.getString("proposed_rev"), row.getString("proposed_op"),
                row.getString("proposed_vvb")))), args);
    }

    private static View view(DynamicProjectScope.Context actor, Revision revision) {
        return new View(actor.id(), actor.environment(), revision.version(), revision.preferences());
    }

    private static Preferences normalize(Preferences input) {
        if (input == null || input.mode() == null) throw invalid("mode");
        String profile = optional(input.declaredProfile(), "declaredProfile", 16);
        if (profile != null && !PROFILES.contains(profile)) throw invalid("declaredProfile");
        var roles = input.responsibilities() == null ? EMPTY_MAP : input.responsibilities();
        return new Preferences(input.mode(), profile, optional(input.declaredCompetence(), "declaredCompetence", 400),
                new Responsibilities(optional(roles.pp(), "pp", 120), optional(roles.prep(), "prep", 120),
                        optional(roles.rev(), "rev", 120), optional(roles.op(), "op", 120),
                        optional(roles.vvb(), "vvb", 120)));
    }

    private static String optional(String value, String field, int maximum) {
        if (value == null || value.isBlank()) return null;
        String result = value.strip();
        if (result.codePointCount(0, result.length()) > maximum
                || result.codePoints().anyMatch(c -> Character.isISOControl(c) && c != '\n' && c != '\r')) {
            throw invalid(field);
        }
        return result;
    }

    private static PilotProblem invalid(String field) {
        return new PilotProblem(400, "PEDIDO_INVALIDO", field, "CF-IMP-011",
                "Revise as preferências e as informações de responsabilidades.");
    }
}
