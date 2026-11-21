package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Set;

/**
 * CF-UX-TECH-001 / #124 UX-08A. Read-only projection of requirements that
 * already exist in applicability and calculation preflight. It does not store
 * tasks, proofs, sources or opinions, and it never admits E8 or a document.
 *
 * The four sheet messages that applicability always appends are not client
 * tasks and are not normative conclusions. A missing evaluator stays
 * "aguardando a equipe". Measured ED/EC does not block an ex ante document.
 */
final class ProjectRequirementProjection {
    static final String RULE = "CF-UX-TECH-001";
    static final String LIMITS = "Nenhum BE, PE, LE ou ER foi calculado. "
        + "Este diagnóstico não aprova adicionalidade, direitos, fonte ou crédito.";

    record Destination(String area, String screen, String step) {}
    record Item(String requirementId, String methodologyId, String edition, String scenario,
            String ruleRef, String state, String situation, String reason, String ownerRole,
            String nextAction, String actionLabel, Destination destination, String group,
            boolean clientAction, boolean blocksExAntePd, boolean admitsE8,
            List<String> affectedOutputs) {}
    record Summary(int mine, int awaitingSource, int awaitingReview, int outOfScope) {}
    record View(String projectId, String methodology, boolean openBranch, boolean diagnosticVisible,
            boolean canRunE8, boolean canIssueVcu, boolean exAnteRequiresMeasuredEnergy,
            String limits, Summary summary, List<Item> requirements) {}
    record Input(String projectId, String methodology, boolean canViewApplicability,
            boolean canViewCalculation, boolean canOpenApplicability, boolean canOpenPreparation,
            boolean canOpenQuantification, boolean canOpenMonitoring,
            OpenApplicability.Readiness applicability, OpenCalculationPreflight.Result preflight) {}

    private static final Set<String> SOURCE_GATES = Set.of("EVR", "AFEC_MPG", "EF_FUEL", "EF_GRID");
    private static final Set<String> REVIEW_GATES = Set.of("NORMATIVE_PACK", "CARBON_RIGHTS", "IR", "DY");

    private ProjectRequirementProjection() {}

    static View project(Input input) {
        boolean open = "VM0038".equals(input.methodology());
        boolean visible = input.canViewApplicability() || input.canViewCalculation();
        var items = new LinkedHashMap<String, Item>();
        if (visible && open) {
            if (input.canViewApplicability()) projectApplicability(input, items);
            if (input.canViewCalculation() && input.preflight() != null) projectPreflight(input, items);
            if (!items.containsKey("ED_EC")) items.put("ED_EC", energyNotRequired(false));
        } else if (visible) {
            items.put("METHODOLOGY_BRANCH", methodologyLimit(input));
        }
        var list = List.copyOf(items.values());
        return new View(input.projectId(), input.methodology(), open, visible,
            false, false, false, LIMITS, summary(list), list);
    }

    private static void projectApplicability(Input input, LinkedHashMap<String, Item> items) {
        OpenApplicability.Readiness readiness = input.applicability();
        if (readiness == null) {
            items.put("NO_DECLARATION", mine(input, "NO_DECLARATION", "ABSENT", "Ainda não informado",
                "A declaração de rede aberta ainda não foi registrada.",
                "Preencher a declaração de acesso, tecnologia e direitos.",
                "Abrir a declaração", destination(input.canOpenApplicability(), "analisar", null),
                input.canOpenApplicability(), List.of("aplicabilidade")));
            return;
        }
        for (var finding : readiness.findings()) {
            Item item = switch (finding.code()) {
                case "VMD0049_ROUTE" -> waitingReview(input, finding.code(),
                    "A lista positiva (VMD0049) ainda não foi avaliada pela equipe. "
                        + "Ela não substitui a aplicabilidade de mercado da VM0038 e não está concluída.",
                    List.of("adicionalidade"));
                case "MARKET_ROUTE" -> waitingSource(input, finding.code(),
                    "O teste de mercado da VM0038 §4 aguarda dados oficiais e a avaliação da equipe. "
                        + "Não é uma rota de adicionalidade, e a ausência de dado não vale zero.",
                    List.of("aplicabilidade"));
                case "REGULATORY_SURPLUS" -> waitingReview(input, finding.code(),
                    "Excedente regulatório, cronologia e adicionalidade aguardam parecer humano. "
                        + "Preencher a declaração não aprova nenhum desses testes.",
                    List.of("adicionalidade"));
                case "ED_EC" -> energyNotRequired(true);
                case "NO_DECLARATION", "ACCESS_UNDETERMINED", "CONFIGURATION_PENDING" ->
                    mine(input, finding.code(), "ABSENT", "Ainda não informado", finding.text(),
                        "Completar somente o que esta declaração pede.",
                        "Abrir a declaração", destination(input.canOpenApplicability(), "analisar", null),
                        input.canOpenApplicability(), List.of("aplicabilidade"));
                case "OWNER_CONTRACT", "DRIVER_DISCLOSURE" ->
                    mine(input, finding.code(), "NEEDS_EVIDENCE", "Falta comprovante", finding.text(),
                        "Anexar ou indicar a referência do comprovante na declaração. "
                            + "O envio não admite o direito.",
                        "Abrir a declaração", destination(input.canOpenApplicability(), "analisar", null),
                        input.canOpenApplicability(), List.of("direitos"));
                case "CLOSED_NETWORK", "CONFIGURATION_BRANCH" ->
                    outside(input, finding.code(), finding.text(),
                        "Rever a declaração ou manter o projeto como hipótese fora deste recorte.",
                        destination(input.canOpenApplicability(), "analisar", null),
                        input.canOpenApplicability());
                case "RIGHTS_DATE_GAP" -> review(input, finding.code(), "RECHECK_REQUIRED",
                    "Precisa rechecagem", finding.text(),
                    "A equipe revisa período e efeitos. Alterar a data não aprova a cronologia.",
                    destination(input.canOpenApplicability(), "analisar", null), false,
                    List.of("direitos", "adicionalidade"));
                case "DOUBLE_CLAIMING" -> review(input, finding.code(), "AWAITING_NORMATIVE_EVALUATION",
                    "Aguardando avaliação da equipe", finding.text(),
                    "Aguardar parecer humano sobre sobreposição. A declaração não exclui outro programa.",
                    destination(input.canOpenApplicability(), "analisar", null), false,
                    List.of("direitos"));
                case "TRIAGE_CHANGED" -> mine(input, finding.code(), "STALE", "Desatualizado",
                    finding.text(), "Reconfirmar a declaração depois da triagem.",
                    "Abrir a declaração", destination(input.canOpenApplicability(), "analisar", null),
                    input.canOpenApplicability(), List.of("aplicabilidade"));
                default -> null;
            };
            if (item != null) items.put(item.requirementId(), item);
        }
    }

    private static void projectPreflight(Input input, LinkedHashMap<String, Item> items) {
        for (var gate : input.preflight().gates()) {
            if (items.containsKey(gate.code()) || "ED".equals(gate.code()) || "EC".equals(gate.code()))
                continue;
            if ("OPEN_APPLICABILITY".equals(gate.code())
                    && (items.containsKey("NO_DECLARATION") || items.containsKey("TRIAGE_CHANGED")))
                continue;
            if ("NETWORK_MODE".equals(gate.code())
                    && (items.containsKey("CLOSED_NETWORK") || items.containsKey("ACCESS_UNDETERMINED")))
                continue;
            if ("TECHNICAL_BRANCH".equals(gate.code())
                    && (items.containsKey("CONFIGURATION_BRANCH") || items.containsKey("CONFIGURATION_PENDING")))
                continue;
            Item item = preflightItem(input, gate);
            if (item != null) items.put(item.requirementId(), item);
        }
    }

    private static Item preflightItem(Input input, OpenCalculationPreflight.Gate gate) {
        boolean stale = "STALE".equals(gate.state());
        if (SOURCE_GATES.contains(gate.code()) || sourcePeriod(gate.code())) {
            return source(input, gate.code(), stale ? "STALE" : "NOT_ADMITTED",
                stale ? "Desatualizado" : "Não admitido para cálculo",
                gate.reason() + " Fonte recebida ou conferida internamente não libera o cálculo.",
                List.of(affected(gate.code())));
        }
        if (REVIEW_GATES.contains(gate.code()) || reviewPeriod(gate.code())) {
            return review(input, gate.code(), stale ? "STALE" : "NOT_ADMITTED",
                stale ? "Desatualizado" : "Não admitido para cálculo",
                gate.reason() + " O estado interno não aprova o requisito.",
                "Aguardar a revisão competente. Nenhuma conta é designada por este diagnóstico.",
                destination(input.canOpenQuantification(), "quantificar", null), false,
                List.of(affected(gate.code())));
        }
        if ("OUT_OF_SCOPE".equals(gate.state())) {
            return outside(input, gate.code(), gate.reason(), gate.nextAction(),
                destination(input.canOpenPreparation(), "preparar", null), input.canOpenPreparation());
        }
        if ("MISSING".equals(gate.state()) || "STALE".equals(gate.state())) {
            boolean prepare = Set.of("COUNTRY", "VEHICLE_CLASS", "METHODOLOGY").contains(gate.code());
            boolean open = input.canOpenPreparation() && prepare || input.canOpenApplicability() && !prepare;
            return mine(input, gate.code(), stale ? "STALE" : "ABSENT",
                stale ? "Desatualizado" : "Ainda não informado", gate.reason(), gate.nextAction(),
                prepare ? "Abrir as informações" : "Abrir a declaração",
                destination(open, prepare ? "preparar" : "analisar", null), open,
                List.of("aplicabilidade"));
        }
        return review(input, gate.code(), "NOT_ADMITTED", "Não admitido para cálculo",
            gate.reason(), "Aguardar a revisão competente.",
            destination(input.canOpenQuantification(), "quantificar", null), false,
            List.of(affected(gate.code())));
    }

    private static boolean sourcePeriod(String code) {
        return code.startsWith("E8_EFEL_") || code.startsWith("E8_VT0011_")
            || code.startsWith("E8_TOOL07_");
    }

    private static boolean reviewPeriod(String code) {
        return code.startsWith("MR_") || code.startsWith("METHOD_REVIEW_")
            || code.startsWith("E8_ENERGY_INPUT_") || code.startsWith("MR_DEPENDENCY_")
            || code.startsWith("MR_PERIOD");
    }

    private static String affected(String code) {
        if (code.startsWith("E8_ENERGY") || "ED".equals(code) || "EC".equals(code)) return "monitoramento";
        if ("EVR".equals(code) || "AFEC_MPG".equals(code) || "EF_FUEL".equals(code)) return "linha de base";
        if (code.startsWith("E8_EFEL") || code.startsWith("E8_VT0011") || code.startsWith("E8_TOOL07")
                || "EF_GRID".equals(code)) return "emissões do projeto";
        return "quantificação";
    }

    private static Item energyNotRequired(boolean fromSheet) {
        return new Item("ED_EC", "VM0038", "1.1", OpenCalculationPreflight.SCENARIO,
            "CF-OPEN-008", "NOT_INCIDENT", "Não se aplica a este ato",
            "Energia entregue (ED) e consumida (EC) pertencem ao monitoramento, por período. "
                + "Não são condição do documento ex ante, e a falta delas não vale zero."
                + (fromSheet ? "" : " Ainda não há medição admitida."),
            "OP", "Nenhuma ação agora. A medição fica em Monitorar quando houver um período.",
            null, null, "OUT_OF_SCOPE", false, false, false, List.of("monitoramento"));
    }

    private static Item methodologyLimit(Input input) {
        boolean known = input.methodology() != null && !input.methodology().isBlank();
        boolean act = input.canOpenPreparation() && !known;
        return new Item("METHODOLOGY_BRANCH", known ? input.methodology() : "UNSET", null, null,
            RULE, known ? "OUT_OF_SCOPE" : "ABSENT",
            known ? "Fora deste recorte" : "Ainda não informado",
            known ? "A metodologia indicada não usa os requisitos OPEN/DCFC desta fatia. "
                    + "O rascunho pode continuar, com esse limite explícito. "
                    + "Nenhum teste de rede aberta foi aplicado."
                : "A metodologia do projeto ainda não foi indicada. "
                    + "Os requisitos OPEN/DCFC não foram aplicados.",
            "PREP", known ? "Continuar o rascunho no recorte indicado, ou rever a triagem."
                : "Indicar a metodologia na preparação ou na triagem.",
            input.canOpenPreparation() ? "Abrir as informações" : null,
            destination(input.canOpenPreparation(), "preparar", null),
            act ? "MINE" : "OUT_OF_SCOPE", act, false, false, List.of("aplicabilidade"));
    }

    private static Item waitingReview(Input input, String id, String reason, List<String> outputs) {
        return review(input, id, "AWAITING_NORMATIVE_EVALUATION", "Aguardando avaliação da equipe",
            reason, "Aguardar a equipe CarbonFlow. Não há campo para preencher no lugar dessa avaliação.",
            null, false, outputs);
    }

    private static Item waitingSource(Input input, String id, String reason, List<String> outputs) {
        return source(input, id, "AWAITING_NORMATIVE_EVALUATION", "Aguardando avaliação da equipe",
            reason, outputs);
    }

    private static Item source(Input input, String id, String state, String situation, String reason,
            List<String> outputs) {
        return new Item(id, "VM0038", "1.1", OpenCalculationPreflight.SCENARIO, "CF-OPEN-008",
            state, situation, reason, "equipe CarbonFlow",
            "Aguardar a equipe CarbonFlow. Uma fonte obtida não libera o cálculo.",
            null, null, "AWAITING_SOURCE", false, false, false, outputs);
    }

    private static Item review(Input input, String id, String state, String situation, String reason,
            String next, Destination destination, boolean clientAction, List<String> outputs) {
        return new Item(id, "VM0038", "1.1", OpenCalculationPreflight.SCENARIO, "CF-OPEN-008",
            state, situation, reason, "REV", next,
            destination == null ? null : "Ver o contexto", destination, "AWAITING_REVIEW",
            clientAction, false, false, outputs);
    }

    private static Item mine(Input input, String id, String state, String situation, String reason,
            String next, String label, Destination destination, boolean clientAction,
            List<String> outputs) {
        return new Item(id, "VM0038", "1.1", OpenCalculationPreflight.SCENARIO, "CF-OPEN-004",
            state, situation, reason, "PREP", next, clientAction ? label : null, destination,
            clientAction ? "MINE" : "AWAITING_REVIEW", clientAction, false, false, outputs);
    }

    private static Item outside(Input input, String id, String reason, String next,
            Destination destination, boolean clientAction) {
        return new Item(id, "VM0038", "1.1", OpenCalculationPreflight.SCENARIO, "CF-OPEN-004",
            "OUT_OF_SCOPE", "Fora deste recorte", reason, "PREP", next,
            clientAction ? "Rever a declaração" : null, destination, "OUT_OF_SCOPE",
            clientAction, false, false, List.of("aplicabilidade"));
    }

    private static Destination destination(boolean allowed, String screen, String step) {
        return allowed ? new Destination("technical", screen, step) : null;
    }

    private static Summary summary(List<Item> items) {
        int mine = 0, source = 0, review = 0, outside = 0;
        for (var item : items) {
            switch (item.group()) {
                case "MINE" -> mine++;
                case "AWAITING_SOURCE" -> source++;
                case "AWAITING_REVIEW" -> review++;
                default -> outside++;
            }
        }
        return new Summary(mine, source, review, outside);
    }
}
