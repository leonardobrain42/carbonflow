package br.carbonflow.pilot;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Pure, read-only explanation of the VM0038 v1.1 arithmetic dependencies.
 * No project values, confidential source candidates, or synthetic results
 * are included. Status remains NOT_CALCULATED until the real #97 runner is
 * admitted and wired to immutable project-scoped inputs.
 */
final class OpenCalculationTransparency {
    static final String REFERENCE = "VM0038@1.1";
    static final String SOURCE_URL =
        "https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/";

    record Stage(String outputCode, String ruleReference, String unit,
                 String description, List<String> requiredInputs,
                 List<String> blockedBy, String status, String whyNotCalculated) {}

    private OpenCalculationTransparency() {}

    static List<Stage> plan(List<OpenCalculationPreflight.Gate> gates) {
        if (gates == null) throw new IllegalArgumentException("Gates ausentes.");
        boolean outside = gates.stream().anyMatch(g -> Set.of(
            "METHODOLOGY", "NETWORK_MODE", "TECHNICAL_BRANCH",
            "COUNTRY", "VEHICLE_CLASS").contains(g.code())
            && "OUT_OF_SCOPE".equals(g.state()));
        String state = outside?"OUT_OF_SCOPE":"NOT_CALCULATED";
        var all = new ArrayList<Stage>();
        all.add(stage("AFEC","VM0038-1.1-EQ2","kWh/100 mi",
            "Consumo elétrico médio ponderado pelo estoque nacional EVR.",
            List.of("EVR", "BEV_RATINGS"),
            List.of("NORMATIVE_PACK","EVR","AFEC_MPG"),gates,state));
        all.add(stage("MPG","VM0038-1.1-EQ3","mi/US gal",
            "Consumo fóssil comparável ponderado pelo mesmo EVR.",
            List.of("EVR","FOSSIL_COMPARABLE_MPG"),
            List.of("NORMATIVE_PACK","EVR","AFEC_MPG"),gates,state));
        all.add(stage("BE","VM0038-1.1-EQ1","tCO2e",
            "ED × EFfuel × 100 × IR^(y−1) ÷ (AFEC × MPG).",
            List.of("ED","EF_FUEL","IR","PROJECT_YEAR","AFEC","MPG"),
            List.of("NORMATIVE_PACK","CARBON_RIGHTS","EVR","AFEC_MPG",
                "EF_FUEL","IR","ED"),gates,state));
        all.add(stage("PE","VM0038-1.1-EQ4","tCO2e",
            "Soma de EC × EFel por hora para o ramo exclusivo SIN.",
            List.of("EC","EF_GRID"),
            List.of("NORMATIVE_PACK","EC","EF_GRID"),gates,state));
        all.add(stage("LE","VM0038-1.1-SECTION-9.3","tCO2e",
            "Fuga considerada de minimis no método; só afirmar após confirmação do ramo e norma.",
            List.of("NORMATIVE_PACK","TECHNICAL_BRANCH"),
            List.of("NORMATIVE_PACK","TECHNICAL_BRANCH"),gates,state));
        all.add(stage("DY","VM0038-1.1-EQ8","adimensional",
            "Razão ERC/(ERF+ERC), ou Dy=1 somente com prova aplicável de ausência de créditos de frota.",
            List.of("ERC","ERF","NO_FLEET_CREDITS_PROOF"),
            List.of("NORMATIVE_PACK","CARBON_RIGHTS","DY"),gates,state));
        all.add(stage("ER","VM0038-1.1-EQ7","tCO2e",
            "(BE − PE) × Dy, dependente de memória e decisão já admitidas.",
            List.of("BE","PE","DY"),
            List.of("NORMATIVE_PACK","CARBON_RIGHTS","EVR","AFEC_MPG",
                "EF_FUEL","IR","ED","EC","EF_GRID","DY"),gates,state));
        return List.copyOf(all);
    }

    private static Stage stage(String code,String reference,String unit,
            String description,List<String> inputs,List<String> dependencies,
            List<OpenCalculationPreflight.Gate> gates,String state) {
        var failures=new ArrayList<String>();
        // Status comes from actual per-project gates; a missing gate cannot be
        // interpreted as permission to run a calculation.
        for (String expected:dependencies) {
            var actual=gates.stream().filter(g -> expected.equals(g.code())).findFirst();
            if (actual.isPresent()) {
                if (!"ADMITTED".equals(actual.get().state()))failures.add(expected);
            } else {
                failures.add(expected);
            }
        }
        var blocked = List.copyOf(failures);
        return new Stage(code,reference,unit,description,inputs,
            blocked,state,why(state,blocked,gates));
    }

    /** Reasons already decided by the preflight. This does not invent a number. */
    private static String why(String state, List<String> blocked,
            List<OpenCalculationPreflight.Gate> gates) {
        if ("OUT_OF_SCOPE".equals(state)) {
            return "Fora do recorte confirmado para este projeto. Nenhum número foi produzido.";
        }
        if (blocked.isEmpty()) {
            return "Não calculado. A execução deste projeto ainda não está admitida, "
                + "então nenhum número foi produzido.";
        }
        var reasons = new ArrayList<String>();
        for (String code : blocked) {
            var gate = gates.stream().filter(item -> code.equals(item.code())).findFirst();
            reasons.add(gate.map(OpenCalculationPreflight.Gate::reason)
                .orElse(code + " ainda não tem avaliação neste projeto."));
        }
        return "Não calculado. " + String.join(" ", reasons);
    }
}
