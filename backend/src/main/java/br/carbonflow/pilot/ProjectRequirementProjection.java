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
    /** Owner signals already decided elsewhere. Missing means the owner has no row. */
    record Owners(boolean rightsRecheck, String fleetState, String bindingState,
            OpenProjectStations.EnergyPosture inventory) {
        static final Owners NONE = new Owners(false, "FLEET_SELECTION_MISSING", "BINDING_MISSING",
            OpenProjectStations.EnergyPosture.NONE);

        Owners(boolean rightsRecheck, String fleetState, String bindingState) {
            this(rightsRecheck, fleetState, bindingState, OpenProjectStations.EnergyPosture.NONE);
        }

        boolean fleetPrepared() {
            return fleetState != null && !fleetState.contains("MISSING");
        }

        boolean bindingPrepared() {
            return bindingState != null && !bindingState.contains("MISSING");
        }
    }
    record Input(String projectId, String methodology, boolean canViewApplicability,
            boolean canViewCalculation, boolean canOpenApplicability, boolean canOpenPreparation,
            boolean canOpenQuantification, boolean canOpenMonitoring,
            OpenApplicability.Readiness applicability, OpenCalculationPreflight.Result preflight,
            Owners owners) {}

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
            applyOwners(input, items);
            if (!items.containsKey("ED_EC")) items.put("ED_EC", energyNotRequired(false));
            applyInventory(input, items);
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

    private static void applyOwners(Input input, LinkedHashMap<String, Item> items) {
        Owners owners = input.owners() == null ? Owners.NONE : input.owners();
        boolean rightsOpen = !items.containsKey("DRIVER_DISCLOSURE") && !items.containsKey("OWNER_CONTRACT");
        if (input.canViewApplicability() && owners.rightsRecheck() && rightsOpen) {
            items.put("RIGHTS_DEPENDENCY", review(input, "RIGHTS_DEPENDENCY", "RECHECK_REQUIRED",
                "Precisa rechecagem",
                "O contrato, a prova de divulgação ou a declaração de sobreposição mudou. "
                    + "A revisão anterior permanece registrada. Esta alteração não aprova direitos nem adicionalidade.",
                "Aguardar a revisão competente desta versão. A declaração nova não conclui o teste.",
                destination(input.canOpenApplicability(), "analisar", null), false,
                List.of("direitos")));
        }
        if (!input.canViewCalculation()) return;
        if (owners.fleetPrepared()) {
            boolean stale = owners.fleetState().startsWith("STALE");
            overlay(items, "EVR", stale, stale
                ? "A seleção preparatória de frota ficou desatualizada. Só este vínculo pede rechecagem."
                : "Há uma seleção preparatória de frota neste projeto. Ela não é fonte admitida e não libera o cálculo.");
            overlay(items, "AFEC_MPG", stale, stale
                ? "Os ratings preparatórios vinculados à frota ficaram desatualizados."
                : "Há ratings preparatórios vinculados à frota. Eles não estão admitidos para a linha de base.");
        }
        if (owners.bindingPrepared()) {
            boolean stale = owners.bindingState().startsWith("STALE");
            overlay(items, "EF_FUEL", stale, stale
                ? "O vínculo preparatório de fatores ficou desatualizado. IR e Dy não mudam de estado por esta seleção."
                : "Há um vínculo preparatório de fatores. Conferência interna não admite o fator fóssil nem libera o cálculo.");
            overlay(items, "EF_GRID", stale, stale
                ? "O fator elétrico preparatório vinculado ao período ficou desatualizado."
                : "Há um fator elétrico preparatório. Ele não é VT0011 admitido e não habilita E8.");
        }
    }

    /**
     * CF-OPEN-009. The open first-slice declaration makes the boundary plan incident.
     * A planned station and a missing series never become measured energy, and neither blocks the ex ante document.
     */
    private static void applyInventory(Input input, LinkedHashMap<String, Item> items) {
        if (!input.canOpenMonitoring() || input.applicability() == null) return;
        boolean incident = input.applicability().findings().stream()
            .anyMatch(finding -> "ED_EC".equals(finding.code()));
        if (!incident) return;
        OpenProjectStations.EnergyPosture posture = input.owners() == null
            || input.owners().inventory() == null
            ? OpenProjectStations.EnergyPosture.NONE : input.owners().inventory();
        boolean act = input.canOpenMonitoring();
        if (!"SELECTED".equals(posture.boundary())) {
            boolean stale = "STALE".equals(posture.boundary());
            items.put("BOUNDARY", monitoring("BOUNDARY", stale ? "STALE" : "ABSENT",
                stale ? "Desatualizado" : "Ainda não informado",
                stale
                    ? "A fronteira aponta revisões antigas de estação ou carregador. "
                        + "Energia entregue e consumida não podem ser presumidas."
                    : "A fronteira ainda não seleciona carregador DCFC do SIN. "
                        + "O documento ex ante pede esse plano. "
                        + "Estação planejada não produz energia medida, e a falta de leitura não vale zero.",
                stale ? "Reconciliar a fronteira com as revisões atuais."
                    : "Selecionar as estações e os carregadores da fronteira.",
                "Abrir as estações", destination(act, "estacoes", null), act, "PREP"));
            return;
        }
        String planned = posture.plannedOnly()
            ? " Nenhuma estação selecionada está em operação declarada, então não há energia medida."
            : "";
        if (!posture.edMeter()) {
            items.put("ED_METER", monitoring("ED_METER", "ABSENT", "Ainda não informado",
                "Falta o medidor da energia entregue (ED), a jusante, distinto da energia consumida."
                    + planned,
                "Cadastrar o instrumento de ED na fronteira atual. O cadastro não admite a série.",
                "Abrir o monitoramento", destination(act, "monitorar", null), act, "OP"));
        }
        if (!posture.ecMeter()) {
            items.put("EC_METER", monitoring("EC_METER", "ABSENT", "Ainda não informado",
                "Falta o medidor da energia consumida (EC), a montante, distinto da energia entregue."
                    + planned,
                "Cadastrar o instrumento de EC na fronteira atual. EC não se deduz de ED.",
                "Abrir o monitoramento", destination(act, "monitorar", null), act, "OP"));
        }
        if (posture.edMeter() && posture.ecMeter()) {
            items.put("ENERGY_SERIES", monitoring("ENERGY_SERIES", "PENDING_MEASUREMENT",
                "Medição pendente",
                "ED e EC seguem separados. Não há série admitida por período. "
                    + "Ausência não vale zero, e calibração declarada não admite o lote." + planned,
                "Registrar as leituras do período quando a operação existir. "
                    + "Cobrança e sessões não são energia medida.",
                "Abrir o monitoramento", destination(act, "monitorar", null), act, "OP"));
        }
    }

    private static Item monitoring(String id, String state, String situation,
            String reason, String next, String label, Destination destination,
            boolean clientAction, String role) {
        return new Item(id, "VM0038", "1.1", OpenCalculationPreflight.SCENARIO, "CF-OPEN-009",
            state, situation, reason, role, next, clientAction ? label : null, destination,
            clientAction ? "MINE" : "AWAITING_REVIEW", clientAction, false, false,
            List.of("monitoramento"));
    }

    private static void overlay(LinkedHashMap<String, Item> items, String code, boolean stale, String reason) {
        Item current = items.get(code);
        if (current == null) return;
        items.put(code, new Item(current.requirementId(), current.methodologyId(), current.edition(),
            current.scenario(), current.ruleRef(), stale ? "STALE" : "SOURCE_CANDIDATE",
            stale ? "Desatualizado" : "Fonte candidata, não admitida", reason,
            "equipe CarbonFlow",
            "Aguardar a equipe CarbonFlow. Preparação interna não admite a fonte nem libera o cálculo.",
            null, null, stale ? "AWAITING_REVIEW" : "AWAITING_SOURCE", false, false, false,
            current.affectedOutputs()));
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
