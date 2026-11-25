package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;

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

    record Applicability(int version, int triageVersion, Boolean unrestrictedBevAccess,
            Boolean restrictedToIdentifiedVehicles, String chargerType,
            String propulsion, String electricitySource,
            Boolean associatedInfrastructure, Boolean v2g) {}

    /**
     * {@code applicabilityStale} is the CF-SPEC-D15 answer (OpenApplicability.stale): a triage revision that changes
     * no relevant answer does not make the declaration stale here either. Without it (pure callers), any newer
     * triage version is treated as stale, which stays conservative.
     */
    record Context(String projectId, String environment, String methodology,
            String country, String vehicleClass,
            int latestTriageVersion, Applicability applicability, boolean applicabilityStale) {
        Context(String projectId, String environment, String methodology, String country, String vehicleClass,
                int latestTriageVersion, Applicability applicability) {
            this(projectId, environment, methodology, country, vehicleClass, latestTriageVersion, applicability,
                applicability != null && applicability.triageVersion() != latestTriageVersion);
        }
    }

    record Gate(String code, String state, String reason, String nextAction, String owner) {}

    record Emissions(String beTco2e, String peTco2e, String leTco2e,
            String erTco2e, String erDisplay) {}

    record Result(String projectId, String environment, String engine,
            String methodology, String scenario, String workflowState,
            String calculationState, boolean canContinuePreparation, boolean canRunE8,
            boolean canIssueVcu, Emissions emissions, List<Gate> gates,
            List<OpenCalculationTransparency.Stage> equationPlan) {}

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
        if (context.country() == null || context.country().isBlank()) {
            gates.add(new Gate("COUNTRY", "MISSING",
                "País do projeto ainda não confirmado.",
                "Registrar o país na preparação técnica", "PREP"));
        } else if (!"BR".equals(context.country())) {
            outside = true;
            gates.add(new Gate("COUNTRY", "OUT_OF_SCOPE",
                "Esta primeira fatia aceita somente projetos localizados no Brasil.",
                "Selecionar cenário geográfico compatível", "PREP"));
        }
        if (context.vehicleClass() == null || context.vehicleClass().isBlank() ||
            "UNKNOWN".equals(context.vehicleClass()) || "DESCONHECIDO".equals(context.vehicleClass())) {
            gates.add(new Gate("VEHICLE_CLASS", "MISSING",
                "A classe de veículos leves (LDV) ainda não foi confirmada na triagem.",
                "Preencher classe da frota na triagem", "PREP"));
        } else if (!"LDV".equals(context.vehicleClass())) {
            outside = true;
            gates.add(new Gate("VEHICLE_CLASS", "OUT_OF_SCOPE",
                "HDV ou classes mistas exigem outro ramo e conjunto de parâmetros.",
                "Revisar classe de veículos e recorte metodológico", "PREP"));
        }
        Applicability a = context.applicability();
        if (a == null) {
            gates.add(new Gate("OPEN_APPLICABILITY", "MISSING",
                "A declaração de rede aberta não foi registrada para este projeto.",
                "Preencher Analisar → Aplicabilidade OPEN", "PREP"));
        } else {
            stale = context.applicabilityStale();
            if (stale) {
                gates.add(new Gate("OPEN_APPLICABILITY", "STALE",
                    "Triagem posterior à declaração: reconfirme a configuração antes do cálculo.",
                    "Rever e salvar aplicabilidade OPEN", "PREP"));
            }
            if (Boolean.FALSE.equals(a.unrestrictedBevAccess()) ||
                Boolean.TRUE.equals(a.restrictedToIdentifiedVehicles())) {
                outside = true;
                gates.add(new Gate("NETWORK_MODE", "OUT_OF_SCOPE",
                    "Rede CLOSED exige outro modelo de frota.",
                    "Rever modalidade e restrições de acesso", "PP/PREP"));
            } else if (!Boolean.TRUE.equals(a.unrestrictedBevAccess()) ||
                       !Boolean.FALSE.equals(a.restrictedToIdentifiedVehicles())) {
                gates.add(new Gate("NETWORK_MODE", "MISSING",
                    "Ainda não se sabe se a rede atende BEVs não identificados.",
                    "Confirmar acesso efetivo OPEN", "PP/PREP"));
            }
            if ("AC".equals(a.chargerType()) || "MIXED".equals(a.chargerType()) ||
                "PHEV".equals(a.propulsion()) || "MIXED".equals(a.propulsion()) ||
                "OTHER_GRID".equals(a.electricitySource()) ||
                "ON_SITE".equals(a.electricitySource()) || "MIXED".equals(a.electricitySource()) ||
                Boolean.TRUE.equals(a.associatedInfrastructure()) || Boolean.TRUE.equals(a.v2g())) {
                outside = true;
                gates.add(new Gate("TECHNICAL_BRANCH", "OUT_OF_SCOPE",
                    "Este ramo exige LDV BEV/DCFC/SIN sem geração, bateria ou V2G associados.",
                    "Selecionar outro ramo metodológico ou rever declaração", "PREP"));
            } else if (!"DCFC".equals(a.chargerType()) || !"BEV".equals(a.propulsion()) ||
                       !"SIN".equals(a.electricitySource()) ||
                       !Boolean.FALSE.equals(a.associatedInfrastructure()) ||
                       !Boolean.FALSE.equals(a.v2g())) {
                gates.add(new Gate("TECHNICAL_BRANCH", "MISSING",
                    "A configuração declarada está incompleta; o ramo ainda não foi definido.",
                    "Confirmar carregador, propulsão, origem e infraestrutura", "PREP"));
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
            "Não há estoque nacional por modelo e ano admitido e vinculado ao projeto.",
            "Selecionar fonte EVR e avaliar cobertura", "PREP");
        add(gates, "AFEC_MPG", "MISSING_BINDING",
            "Ratings PBEV e comparáveis revisados não foram vinculados ao estoque nacional do projeto.",
            "Revisar ciclo, modelo, fonte e comparabilidade", "PREP/REV");
        add(gates, "EF_FUEL", "MISSING_BINDING",
            "O fator fóssil por combustível, ano e gás não está admitido para a linha de base.",
            "Solicitar fonte brasileira e parecer contextual", "PREP/REV");
        add(gates, "IR", "MISSING_BINDING",
            "A melhoria tecnológica não possui regra e prova aplicáveis. A preparação não usa 1 por padrão.",
            "Estabelecer a regra e o período de referência da melhoria tecnológica", "REV");
        add(gates, "DY", "MISSING_BINDING",
            "O desconto por outro crédito depende de direitos e de sobreposição. A preparação não assume 1 nem zero.",
            "Concluir a revisão de dupla contagem e do desconto", "REV");
        add(gates, "ED", "PENDING_MEASUREMENT",
            "Não há energia entregue admitida por carregador e período.",
            "Preparar a medição da energia entregue e a calibração", "OP/PREP");
        add(gates, "EC", "PENDING_MEASUREMENT",
            "Não há energia consumida admitida por carregador e período. Energia entregue e energia consumida são medições diferentes.",
            "Preparar a medição da energia consumida e a reconciliação com a energia entregue", "OP/PREP");
        add(gates, "EF_GRID", "MISSING_BINDING",
            "O fator elétrico do SIN, com período de referência e eventual janela horária, não está admitido. A avaliação segue VT0011 e TOOL07.",
            "Avaliar a série elétrica e o método vigente", "PREP/REV");

        String calculationState = outside || stale
            ? "BLOCKED_NOT_ADMISSIBLE" : "BLOCKED_MISSING_DATA";
        return new Result(context.projectId(), context.environment(), ENGINE,
            context.methodology(), SCENARIO, "REQUEST_DATA", calculationState,
            true, false, false, new Emissions(null, null, null, null, null),
            List.copyOf(gates),OpenCalculationTransparency.plan(gates));
    }

    private static void add(List<Gate> result, String code, String state,
            String reason, String action, String owner) {
        result.add(new Gate(code, state, reason, action, owner));
    }
}
