package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Tenant-scoped read-only bridge to the real dynamic project domain.
 * Staff-only source candidates are intentionally not read from project routes.
 * A preparatory record cannot be treated as an admitted E8 parameter.
 */
@ApplicationScoped
class OpenCalculationReadiness {
    private final DynamicProjectScope scope;

    OpenCalculationReadiness(DynamicProjectScope scope) {
        this.scope = scope;
    }

    OpenCalculationPreflight.Result get(String cookie, String projectId) {
        return scope.read(cookie, projectId, "VIEW_OPEN_CALCULATION",
            (connection, context) -> OpenCalculationPreflight.evaluate(
                new OpenCalculationPreflight.Context(context.id(),
                    context.environment(), methodology(connection, context.id()),
                    triageRevision(connection, context.id()),
                    applicability(connection, context.id()))));
    }

    private static String methodology(Connection connection, String project) throws SQLException {
        String selected = ProjectTriageWizard.savedCandidate(connection, project);
        if (selected == null || selected.isBlank()) {
            selected = first(connection, """
                    SELECT payload->>'candidateMethodology'
                    FROM app_project_intake_draft_revision
                    WHERE project_id = ? ORDER BY version DESC LIMIT 1
                    """, row -> row.getString(1), project);
        }
        return ProjectJourney.profile(selected) == ProjectJourney.VM0038
            ? "VM0038" : selected;
    }

    private static int triageRevision(Connection connection, String project) throws SQLException {
        Integer revision = first(connection, """
                SELECT coalesce(max(version),0)
                FROM app_project_triage_revision WHERE project_id = ?
                """, row -> row.getInt(1), project);
        return revision == null ? 0 : revision;
    }

    private static OpenCalculationPreflight.Applicability applicability(
            Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT version,triage_version,
                       unrestricted_bev_access,restricted_to_identified_vehicles,
                       charger_type,propulsion,electricity_source,
                       associated_infrastructure,v2g
                FROM app_project_open_applicability_revision
                WHERE project_id=? ORDER BY version DESC LIMIT 1
                """, row -> new OpenCalculationPreflight.Applicability(
                    row.getInt("version"), row.getInt("triage_version"),
                    (Boolean) row.getObject("unrestricted_bev_access"),
                    (Boolean) row.getObject("restricted_to_identified_vehicles"),
                    row.getString("charger_type"), row.getString("propulsion"),
                    row.getString("electricity_source"),
                    (Boolean) row.getObject("associated_infrastructure"),
                    (Boolean) row.getObject("v2g")), project);
    }
}
