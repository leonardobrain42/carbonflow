package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * CF-OPEN-008 / #97: deterministic fail-closed preflight for dynamic OPEN
 * project calculations. This is NOT an E8 execution engine and never reads
 * or substitutes PIL-SYN-BR-001/EF-SYN-v1 or any pilot snapshot.
 *
 * Data presence, independent source admission, project binding and external
 * verification are different gates. The first cut has no project-scoped
 * approved bindings or admitted ED/EC snapshots: all numeric results stay null.
 */
final class OpenCalculationPreflight {
    static final String SCENARIO = "OPEN_LDV_DCFC_SIN_BR";
    static final String ENGINE = "vm0038-open-preflight@1";

    record Applicability(int version, int triageVersion, boolean unrestrictedBevAccess,
            boolean restrictedToIdentifiedVehicles, String chargerType,
            String propulsion, String electricitySource,
            boolean associatedInfrastructure, boolean v2g) {}

    record Context(String projectId, String environment, String methodology,
            int latestTriageVersion, Applicability applicability) {}

    record Gate(String code, String state, String reason, String nextAction, String owner) {}

    record Emissions(String beTco2e, String peTco2e, String leTco2e,
            String erTco2e, String erDisplay) {}

    record Result(String projectId, String environment, String engine,
            String methodology, String scenario, String workflowState,
            String calculationState, boolean canContinuePreparation, boolean canRunE8,
            boolean canIssueVcu, Emissions emissions, List<Gate> gates) {}

    private OpenCalculationPreflight() {}

    static Result evaluate(Context context) {
        var gates = new ArrayList<Gate>();
        boolean outside = false;
        boolean stale = false;
        if (!"VM0038".equals(context.methodology())) {
            outside = true;
            gates.add(new Gate("METHODOLOGY", "OUT_OF_SCOPE",
                "Selecione e confirme VM0038 para avaliar o ramo OPEN brasileiro.",
                "Revisar triagem e metodologia candidata", "PP/PREP"));
        }
        Applicability a = context.applicability();
        if (a == null) {
            gates.add(new Gate("OPEN_APPLICABILITY", "MISSING",
                "A declaração de rede aberta não foi registrada para este projeto.",
                "Preencher Analisar → Aplicabilidade OPEN", "PREP"));
        } else {
            stale = a.triageVersion() != context.latestTriageVersion();
            if (stale) {
                gates.add(new Gate("OPEN_APPLICABILITY", "STALE",
                    "Triagem posterior à declaração: reconfirme a configuração antes do cálculo.",
                    "Rever e salvar aplicabilidade OPEN", "PREP"));
            }
            if (!a.unrestrictedBevAccess() || a.restrictedToIdentifiedVehicles()) {
                outside = true;
                gates.add(new Gate("NETWORK_MODE", "OUT_OF_SCOPE",
                    "Rede CLOSED ou acesso não comprovadamente OPEN exige outro modelo de frota.",
                    "Rever modalidade e restrições de acesso", "PP/PREP"));
            }
            if (!"DCFC".equals(a.chargerType()) || !"BEV".equals(a.propulsion()) ||
                !"SIN".equals(a.electricitySource()) || a.associatedInfrastructure() ||
                a.v2g()) {
                outside = true;
                gates.add(new Gate("TECHNICAL_BRANCH", "OUT_OF_SCOPE",
                    "Este ramo exige LDV BEV/DCFC/SIN sem geração, bateria ou V2G associados.",
                    "Selecionar outro ramo metodológico ou rever declaração", "PREP"));
            }
        }

        // There is currently NO approved ProjectParameterBinding/PeriodEnergySnapshot
        // for a dynamic project. Do not query staff-internal candidate values or
        // report their existence to users, and never inherit pilot data.
        add(gates, "NORMATIVE_PACK", "NOT_ADMITTED",
            "Regra VM0038/edição/ato real não está vinculada e publicada para este projeto.",
            "Revisão normativa com fonte, edição e vigência", "REV");
        add(gates, "CARBON_RIGHTS", "NOT_ADMITTED",
            "Declaração de titularidade não equivale a prova jurídica e revisão independente.",
            "Obter e revisar direitos e prevenção de dupla contagem", "PP/REV");
        add(gates, "EVR", "MISSING_BINDING",
            "Não há snapshot nacional EVR por modelo/ano admitido e vinculado ao projeto.",
            "Selecionar fonte EVR e avaliar cobertura", "PREP");
        add(gates, "AFEC_MPG", "MISSING_BINDING",
            "Ratings PBEV e comparáveis revisados não foram vinculados ao EVR do projeto.",
            "Revisar ciclo, modelo, fonte e comparabilidade", "PREP/REV");
        add(gates, "EF_FUEL", "MISSING_BINDING",
            "Fator fóssil por combustível/ano/gás não admitido para a linha de base.",
            "Solicitar fonte brasileira e parecer contextual", "PREP/REV");
        add(gates, "IR", "MISSING_BINDING",
            "Melhoria tecnológica IR não possui regra e prova aplicáveis; não usar 1 por padrão.",
            "Estabelecer regra e vintage de IR", "REV");
        add(gates, "DY", "MISSING_BINDING",
            "Desconto Dy depende de direitos e sobreposição: não assumir 1 ou zero.",
            "Concluir revisão de dupla contagem e desconto", "REV");
        add(gates, "ED", "PENDING_MEASUREMENT",
            "Não há energia entregue ED admitida por carregador e período.",
            "Preparar medição ED e calibração", "OP/PREP");
        add(gates, "EC", "PENDING_MEASUREMENT",
            "Não há energia consumida EC admitida por carregador e período; ED ≠ EC.",
            "Preparar medição EC e reconciliação com ED", "OP/PREP");
        add(gates, "EF_GRID", "MISSING_BINDING",
            "EFel para SIN, vintage e eventual janela horária não está admitido sob VT0011/TOOL07.",
            "Avaliar série elétrica e método vigente", "PREP/REV");

        String calculationState = outside || stale
            ? "BLOCKED_NOT_ADMISSIBLE" : "BLOCKED_MISSING_DATA";
        return new Result(context.projectId(), context.environment(), ENGINE,
            context.methodology(), SCENARIO, "REQUEST_DATA", calculationState,
            true, false, false, new Emissions(null, null, null, null, null),
            List.copyOf(gates));
    }

    private static void add(List<Gate> result, String code, String state,
            String reason, String action, String owner) {
        result.add(new Gate(code, state, reason, action, owner));
    }
}
