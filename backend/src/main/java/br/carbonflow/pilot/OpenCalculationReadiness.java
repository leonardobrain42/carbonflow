package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.List;

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
            (connection, context) -> {
                var preflight=OpenCalculationPreflight.evaluate(
                    new OpenCalculationPreflight.Context(context.id(),
                        context.environment(), methodology(connection, context.id()),
                        country(connection, context.id()), vehicleClass(connection, context.id()),
                        triageRevision(connection, context.id()),
                        applicability(connection, context.id())));
                return withMrDependencies(connection,context.id(),preflight);
            });
    }

    /**
     * Project-scoped MR dependency diagnostic. Shows only workflow status to
     * collaborators with CALCULATION grant; does not disclose raw ED/EC, files,
     * private contracts, or internal staff source candidates.
     */
    private static OpenCalculationPreflight.Result withMrDependencies(
            Connection connection,String project,OpenCalculationPreflight.Result base)
        throws SQLException {
        // Do not impose VM0038/OPEN MR requirements on a different
        // methodology or on projects still choosing their methodology.
        if(!"VM0038".equals(base.methodology()))return base;
        var gates=new ArrayList<>(base.gates());
        var manifest=OpenMonitoringDossiers.manifest(connection,project);
        if(manifest.dossiers().isEmpty()) {
            gates.add(new OpenCalculationPreflight.Gate("MR_PERIOD_DEPENDENCY",
                "PENDING_MEASUREMENT",
                "Ainda não existe dossiê MR vinculado a um período reconciliado neste projeto.",
                "Preparar MR por período e revisar evidências de monitoramento","PREP/REV"));
        } else {
            for(var dossier:manifest.dossiers()) {
                String status=dossier.stale()?"STALE":
                    "READY_FOR_NORMATIVE_REVIEW".equals(dossier.state())?
                    "NOT_ADMITTED":"REVIEW_REQUIRED";
                String reason=dossier.stale() ?
                    "O período "+dossier.reference().code()+
                    " depende de medição ou parecer já obsoleto; recalcular QA, gerar novo snapshot e atualizar MR." :
                    "O período "+dossier.reference().code()+
                    " ainda não passou pela admissão metodológica. QA/documentação interna não libera E8.";
                gates.add(new OpenCalculationPreflight.Gate(
                    "MR_DEPENDENCY_"+dossier.reference().code(),
                    status,reason,"Revisar vínculo e provas de MR do período","PREP/REV"));
            }
        }
        return new OpenCalculationPreflight.Result(base.projectId(),base.environment(),
            base.engine(),base.methodology(),base.scenario(),base.workflowState(),
            base.calculationState(),base.canContinuePreparation(),false,false,
            base.emissions(),List.copyOf(gates));
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

    private static String country(Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT payload->>'country' FROM app_project_intake_draft_revision
                WHERE project_id=? ORDER BY version DESC LIMIT 1
                """, row -> row.getString(1), project);
    }

    private static String vehicleClass(Connection connection, String project) throws SQLException {
        return first(connection, """
                SELECT answers #>> '{3,vehicleClass}'
                FROM app_project_triage_revision
                WHERE project_id=? ORDER BY version DESC LIMIT 1
                """, row -> row.getString(1), project);
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
