package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

final class ProjectCollaboration {
    private record Grant(String role, Set<String> activities) {}
    record Access(String role, Set<String> actions) {}

    static final Set<String> OWNER_TECHNICAL_ACTIONS = Set.of("VIEW_PROJECT_METADATA", "VIEW_CAPABILITIES", "VIEW_JOURNEY",
            "VIEW_PD_REVIEW", "VIEW_GUIDES", "SAVE_GUIDE", "VIEW_INTAKE", "SAVE_INTAKE", "VIEW_DOSSIER", "PREPARE_DOSSIER", "DOWNLOAD_DOSSIER",
            "VIEW_OPEN_APPLICABILITY", "SAVE_OPEN_APPLICABILITY", "VIEW_OPEN_CALCULATION",
            "VIEW_OPEN_MONITORING", "SAVE_OPEN_MONITORING", "SUBMIT_OPEN_MEASUREMENTS",
            "VIEW_OPEN_PERIOD_ADMISSION", "SUBMIT_OPEN_PERIOD_EVIDENCE",
            "SUBMIT_OPEN_MONITORING_EVENT", "VIEW_OPEN_METHOD_REVIEW",
            "VIEW_OPEN_E8_ENERGY_CANDIDATE", "PREPARE_OPEN_E8_ENERGY_CANDIDATE");

    private ProjectCollaboration() {}

    static Set<String> actions(Connection connection, String project, UUID actor) throws SQLException {
        return access(connection, project, actor).actions();
    }

    static Access access(Connection connection, String project, UUID actor) throws SQLException {
        List<Grant> grants = query(connection, """
                SELECT role, activities FROM app_project_collaborator_grant
                WHERE project_id = ? AND identity_id = ? AND revoked_at IS NULL
                  AND valid_from <= statement_timestamp() AND valid_until > statement_timestamp()
                """, row -> {
            var array = row.getArray("activities");
            try {
                return new Grant(row.getString("role"), Set.copyOf(List.of((String[]) array.getArray())));
            } finally {
                array.free();
            }
        }, project, actor);
        if (grants.stream().map(Grant::role).distinct().count() != 1) return new Access(null, Set.of());

        var actions = new LinkedHashSet<>(List.of("VIEW_PROJECT_METADATA", "VIEW_CAPABILITIES", "VIEW_JOURNEY"));
        for (var grant : grants) {
            boolean preparer = "PREP".equals(grant.role());
            for (String activity : grant.activities()) {
                if (!"DOCUMENTATION".equals(activity)) {
                    actions.add("GUIDE_" + activity);
                    actions.add("VIEW_GUIDES");
                    if ("CALCULATION".equals(activity))
                        actions.add("VIEW_OPEN_CALCULATION");
                    if ("MONITORING".equals(activity)) {
                        actions.add("VIEW_OPEN_MONITORING");
                        actions.add("VIEW_OPEN_PERIOD_ADMISSION");
                        if (!"OP".equals(grant.role())) {
                            actions.add("VIEW_OPEN_METHOD_REVIEW");
                            actions.add("VIEW_OPEN_E8_ENERGY_CANDIDATE");
                        }
                        if (preparer)
                            actions.add("PREPARE_OPEN_E8_ENERGY_CANDIDATE");
                        if (preparer) actions.add("SAVE_OPEN_MONITORING");
                        if (preparer || "OP".equals(grant.role())) {
                            actions.add("SUBMIT_OPEN_MEASUREMENTS");
                            actions.add("SUBMIT_OPEN_PERIOD_EVIDENCE");
                            actions.add("SUBMIT_OPEN_MONITORING_EVENT");
                        }
                        if ("REV".equals(grant.role())) {
                            actions.add("REVIEW_OPEN_MEASUREMENTS");
                            actions.add("SNAPSHOT_OPEN_PERIOD");
                            actions.add("REVIEW_OPEN_PERIOD_EVIDENCE");
                            actions.add("REVIEW_OPEN_MONITORING_EVENT");
                            actions.add("REVIEW_OPEN_METHOD_REVIEW");
                        }
                    }
                    if ("APPLICABILITY".equals(activity)) {
                        actions.add("VIEW_OPEN_APPLICABILITY");
                        if (preparer) actions.add("SAVE_OPEN_APPLICABILITY");
                    }
                    if (preparer) actions.add("SAVE_GUIDE");
                }
            }
            if (grant.activities().contains("INTAKE")) {
                actions.add("VIEW_INTAKE");
                if (preparer) actions.add("SAVE_INTAKE");
            }
            if (grant.activities().contains("DOCUMENTATION")) {
                // A preliminary dossier includes its intake snapshot. Preparing it also requires viewing current intake.
                actions.addAll(List.of("VIEW_INTAKE", "VIEW_DOSSIER", "DOWNLOAD_DOSSIER", "VIEW_PD_REVIEW"));
                if (!preparer) actions.add("DECIDE_PD_REVIEW");
                if (preparer) actions.add("PREPARE_DOSSIER");
            }
        }
        return new Access(grants.getFirst().role(), Set.copyOf(actions));
    }
}
