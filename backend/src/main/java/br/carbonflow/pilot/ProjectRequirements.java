package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;

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
                connection, context.id(), context.environment()) : null));
    }
}
