package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;

/**
 * Assembles the UX-08 requirement projection from the owners that already
 * decide applicability and calculation readiness. Grants are applied before
 * any item is built. Nothing is written.
 */
@ApplicationScoped
class ProjectRequirements {
    private final DynamicProjectScope scope;

    ProjectRequirements(DynamicProjectScope scope) {
        this.scope = scope;
    }

    ProjectRequirementProjection.View get(String cookie, String project) {
        return scope.read(cookie, project, "VIEW_JOURNEY", ProjectRequirements::assemble);
    }

    /** Same projection the journey overview embeds. Grants are applied before any item. */
    static ProjectRequirementProjection.View assemble(Connection connection,
            DynamicProjectScope.Context context) throws SQLException {
        var actions = context.actions();
        boolean applicability = actions.contains("VIEW_OPEN_APPLICABILITY");
        boolean calculation = actions.contains("VIEW_OPEN_CALCULATION");
        String methodology = OpenCalculationReadiness.methodology(connection, context.id());
        boolean open = "VM0038".equals(methodology);
        return ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            context.id(), methodology, applicability, calculation,
            applicability, actions.contains("VIEW_INTAKE"), calculation,
            actions.contains("VIEW_OPEN_MONITORING"),
            applicability && open ? OpenApplicability.currentReadiness(connection, context.id()) : null,
            calculation && open ? OpenCalculationReadiness.diagnose(
                connection, context.id(), context.environment()) : null,
            owners(connection, context.id(), applicability, calculation,
                actions.contains("VIEW_OPEN_MONITORING"))));
    }

    private static ProjectRequirementProjection.Owners owners(Connection connection, String project,
            boolean applicability, boolean calculation, boolean monitoring) throws SQLException {
        String fleet = "FLEET_SELECTION_MISSING";
        String binding = "BINDING_MISSING";
        if (calculation) {
            for (var dossier : OpenMonitoringDossiers.manifest(connection, project).dossiers()) {
                String period = dossier.reference().code();
                fleet = prefer(fleet, OpenE8FleetSelections.currentState(connection, project, period));
                binding = prefer(binding, OpenE8InputBindings.currentState(connection, project, period));
            }
        }
        return new ProjectRequirementProjection.Owners(
            applicability && rightsRecheck(connection, project), fleet, binding,
            monitoring ? OpenProjectStations.posture(connection, project)
                : OpenProjectStations.EnergyPosture.NONE);
    }

    /** A present proof that changed needs review. A still-missing proof stays a gap. */
    private static boolean rightsRecheck(Connection connection, String project) throws SQLException {
        List<Rights> revisions = query(connection, """
            SELECT carbon_rights_contract_ref, driver_disclosure_evidence_ref,
                   driver_disclosure_effective_on::text, double_claiming_check,
                   rights_party_code, rights_contract_code,
                   operational_event_code, vcs_event_code
            FROM app_project_open_applicability_revision
            WHERE project_id=? ORDER BY version DESC LIMIT 2
            """, row -> new Rights(row.getString(1), row.getString(2), row.getString(3), row.getString(4),
                row.getString(5), row.getString(6), row.getString(7), row.getString(8)),
            project);
        if (revisions.size() < 2) return false;
        Rights current = revisions.get(0);
        Rights previous = revisions.get(1);
        boolean present = current.party() != null && !current.party().isBlank()
            && current.contractRecord() != null && !current.contractRecord().isBlank()
            && current.disclosure() != null && !current.disclosure().isBlank();
        return present && !current.equals(previous);
    }

    private static String prefer(String current, String candidate) {
        if (candidate != null && candidate.startsWith("STALE")) return candidate;
        if (current != null && current.startsWith("STALE")) return current;
        if (candidate != null && !candidate.contains("MISSING")) return candidate;
        return current;
    }

    private record Rights(String contract, String disclosure, String effectiveOn, String overlap,
        String party, String contractRecord, String operationalEvent, String vcsEvent) {}
}
