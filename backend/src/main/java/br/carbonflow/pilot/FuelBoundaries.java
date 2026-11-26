package br.carbonflow.pilot;

import java.util.List;
import java.util.Set;

/**
 * Candidate GHG boundaries for the fossil-fuel combustion of VM0038 v1.1 (method-edition level, D17: this is NOT platform
 * core). Source read in the primary text on 25/11/2026: section 6, Table 1, p. 12. Baseline: CO2 yes; CH4 and N2O
 * optional ("may be excluded for simplification (conservative)"). Project: CO2 yes; CH4 and N2O optional, and "where
 * included in the baseline, source must be accounted for in project emissions". Nothing here admits a factor.
 */
final class FuelBoundaries {
    static final String METHOD = "VM0038@1.1";
    static final String SOURCE = "VM0038 v1.1 (19/08/2026), seção 6, Tabela 1, p. 12; parâmetro EFi,f,y, seção 10.2: "
            + "\"unidades (CO2 ou CO2e) consistentes com as escolhas da fronteira e entre todas as fontes\"";
    static final Set<String> GASES = Set.of("CO2", "CH4", "N2O");

    record Boundary(String id, String name, List<String> baselineGases, List<String> projectGases, String note,
                    String method, String source, String status) {}

    static final List<Boundary> PRESETS = List.of(
            new Boundary("CO2_ONLY", "Somente CO2 na linha de base e no projeto", List.of("CO2"), List.of("CO2"),
                    "CH4 e N2O excluídos por simplificação (conservador, Tabela 1). Os fatores de energia elétrica do projeto também têm de ser só de CO2; "
                            + "a série de despacho do MCTI é só CO2.", METHOD, SOURCE, "CANDIDATE"),
            new Boundary("CO2_CH4_N2O", "CO2, CH4 e N2O na linha de base e no projeto", List.of("CO2", "CH4", "N2O"),
                    List.of("CO2", "CH4", "N2O"),
                    "Exige fator de CH4 e de N2O também para o consumo de eletricidade do projeto, ou um CO2e agregado com a base de GWP declarada.",
                    METHOD, SOURCE, "CANDIDATE"));

    private FuelBoundaries() {}

    static Boundary preset(String id) {
        return PRESETS.stream().filter(b -> b.id().equals(id)).findFirst().orElse(null);
    }

    /** Table 1 rules: CO2 always in; CH4/N2O optional; what the baseline includes the project must include. */
    static List<String> inconsistencies(List<String> baseline, List<String> project) {
        var problems = new java.util.ArrayList<String>();
        if (!baseline.contains("CO2") || !project.contains("CO2")) problems.add("CO2 é obrigatório na linha de base e no projeto.");
        if (!GASES.containsAll(baseline) || !GASES.containsAll(project)) problems.add("Gás fora de CO2, CH4 e N2O.");
        if (!project.containsAll(baseline)) problems.add("Gás incluído na linha de base precisa ser contabilizado nas emissões do projeto.");
        return problems;
    }
}
