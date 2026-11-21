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
            (connection, context) -> diagnose(connection, context.id(), context.environment()));
    }

    /** Same fail-closed diagnosis the calculation screen already reads. */
    static OpenCalculationPreflight.Result diagnose(Connection connection, String projectId,
            String environment) throws SQLException {
        var preflight=OpenCalculationPreflight.evaluate(
            new OpenCalculationPreflight.Context(projectId, environment,
                methodology(connection, projectId), country(connection, projectId),
                vehicleClass(connection, projectId), triageRevision(connection, projectId),
                applicability(connection, projectId)));
        return withMrDependencies(connection, projectId, preflight);
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
                String methodState=OpenMethodReview.status(connection,project,
                    dossier.reference().code());
                gates.add(new OpenCalculationPreflight.Gate(
                    "METHOD_REVIEW_"+dossier.reference().code(),
                    "BLOCKED_STALE".equals(methodState)?"STALE":
                    "CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION".equals(methodState)?
                    "NOT_ADMITTED":"REVIEW_REQUIRED",
                    "Avaliação VM0038 v1.1 ("+dossier.reference().code()+"): "+
                        plainStatus(methodState)+
                        ". Parecer interno não admite energia para o cálculo.",
                    "Conferência normativa e decisão independente de admissibilidade","REV"));
                String energyState=OpenE8EnergyCandidates.state(connection,project,
                    dossier.reference().code());
                gates.add(new OpenCalculationPreflight.Gate(
                    "E8_ENERGY_INPUT_"+dossier.reference().code(),
                    "STALE_NOT_ADMITTED".equals(energyState)?"STALE":
                    "CANDIDATE_MISSING".equals(energyState)?"PENDING_MEASUREMENT":"NOT_ADMITTED",
                    "Pacote horário ED/EC do período "+dossier.reference().code()+
                        ": "+plainStatus(energyState)+
                        ". A preparação não substitui admissão normativa nem habilita o motor.",
                    "Conferir cadeia de evidências e completar os parâmetros do E8","PREP/REV"));
                String efelState=OpenEfelCandidates.state(connection,project,
                    dossier.reference().code());
                gates.add(new OpenCalculationPreflight.Gate(
                    "E8_EFEL_"+dossier.reference().code(),
                    "STALE_NOT_ADMITTED".equals(efelState)?"STALE":
                    "EFEL_SOURCE_MISSING".equals(efelState)?"MISSING":"NOT_ADMITTED",
                    "Fator EFel horário do período "+dossier.reference().code()+
                        ": "+plainStatus(efelState)+
                        ". Uma série declarada e conferida internamente não é VT0011 admitido.",
                    "Validar série de fatores, instrumento e direitos em fonte autorizada",
                    "PREP/REV"));
                // Source-to-project handoffs are visible only as status,
                // never as the numerical staff catalogue. Prepared != admitted.
                String period=dossier.reference().code();
                String inputBinding=OpenE8InputBindings.currentState(
                    connection,project,period);
                String fleetSelection=OpenE8FleetSelections.currentState(
                    connection,project,period);
                gates.addAll(OpenPreparatoryE8Diagnostic.gates(
                    period,inputBinding,fleetSelection));
                String vt0011=OpenVt0011Workbooks.state(connection,project,
                    dossier.reference().code());
                gates.add(new OpenCalculationPreflight.Gate(
                    "E8_VT0011_CASE2_"+dossier.reference().code(),
                    vt0011.startsWith("STALE")?"STALE":
                    "VT0011_WORKBOOK_MISSING".equals(vt0011) ||
                    "VT0011_SOURCE_MISSING".equals(vt0011)?"MISSING":"NOT_ADMITTED",
                    "Caderno VT0011/TOOL07 do período "+dossier.reference().code()+
                        ": "+plainStatus(vt0011)+
                        ". Pesos Case 2 não verificam OM nem convertem CO2 em CO2e.",
                    "Comprovar dados OM, opção TOOL07, fontes e vínculo horário ao EFel",
                    "PREP/REV"));
                String simpleOm=OpenTool07SimpleOmRuns.state(connection,project,
                    dossier.reference().code());
                gates.add(new OpenCalculationPreflight.Gate(
                    "E8_TOOL07_SIMPLE_OM_"+dossier.reference().code(),
                    simpleOm.startsWith("STALE")?"STALE":
                    "SIMPLE_OM_A1_MISSING".equals(simpleOm)?"MISSING":"NOT_ADMITTED",
                    "Apuração preliminar Simple OM A1 de "+
                        dossier.reference().code()+": "+plainStatus(simpleOm)+
                        ". Triagem LCMR e aritmética por usina não substituem a "+
                        "validação das fontes, importações, anos ou CO2/CO2e.",
                    "Verificar integralidade das usinas, combustíveis, dados oficiais e "+
                        "requisitos condicionais TOOL07 antes da admissibilidade",
                    "PREP/REV"));
            }
        }
        return new OpenCalculationPreflight.Result(base.projectId(),base.environment(),
            base.engine(),base.methodology(),base.scenario(),base.workflowState(),
            base.calculationState(),base.canContinuePreparation(),false,false,
            base.emissions(),List.copyOf(gates),
            OpenCalculationTransparency.plan(gates));
    }

    static String methodology(Connection connection, String project) throws SQLException {
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

    /** Visible situation for a stored status. The code itself stays off the sentence. */
    static String plainStatus(String code) {
        return switch (code==null ? "" : code) {
            case "BLOCKED_STALE" ->
                "as evidências deixaram de corresponder ao período";
            case "BLOCKED_DOCUMENTARY" ->
                "há documentos ausentes ou ainda não conferidos";
            case "PENDING_INTERNAL_REVIEW" ->
                "o parecer interno ainda não foi registrado";
            case "RETURNED_FOR_CORRECTION" ->
                "o parecer interno pediu correção";
            case "CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION" ->
                "há parecer interno e a admissão normativa ainda não existe";
            case "CANDIDATE_MISSING" ->
                "ainda não há pacote horário neste período";
            case "STALE_NOT_ADMITTED" ->
                "o registro ficou desatualizado e não está admitido";
            case "NOT_ADMITTED" ->
                "há registro interno e a admissão normativa ainda não existe";
            case "EFEL_SOURCE_MISSING" ->
                "ainda não há série declarada neste período";
            case "SUPERSEDED_NOT_ADMITTED" ->
                "há um registro mais recente e este não está admitido";
            case "PENDING_INTERNAL_REVIEW_NOT_ADMITTED" ->
                "aguarda conferência interna e não está admitido";
            case "RETURNED_NOT_ADMITTED" ->
                "a conferência interna devolveu o registro e ele não está admitido";
            case "CHECKED_INTERNAL_NOT_ADMITTED" ->
                "foi conferido internamente e não está admitido";
            case "VT0011_SOURCE_MISSING" ->
                "ainda não há fator elétrico para montar o caderno";
            case "VT0011_WORKBOOK_MISSING" ->
                "ainda não há caderno neste período";
            case "SIMPLE_OM_A1_MISSING" ->
                "ainda não há apuração neste período";
            case "LCMR_SCREEN_PASSED_PRELIMINARY_NOT_ADMITTED" ->
                "a triagem preliminar passou e a admissão ainda não existe";
            case "LCMR_SCREEN_FAILED_PRELIMINARY_NOT_ADMITTED" ->
                "a triagem preliminar não passou e a admissão ainda não existe";
            default -> "situação ainda sem texto público";
        };
    }
}
