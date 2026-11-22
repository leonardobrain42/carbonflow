package br.carbonflow.pilot;

import java.math.BigDecimal;

/**
 * CF-OPEN-007 / #96. Distinguishes a missing parameter, an explicit zero and
 * an explicit one. Does not admit a factor to E8 and does not invent 1.
 */
final class OpenProjectParameterRules {
    static final String RULE = "CF-OPEN-007";

    record Scalar(String valueKind, String canonical, boolean explicitOne) {}

    private OpenProjectParameterRules() {}

    static void factor(String kind, int year, int candidateYear, String gas,
                       String fuel, String grid, String unit) {
        if (year != candidateYear) {
            throw invalid("O ano do pino precisa ser o ano da fonte. Anos diferentes bloqueiam o vínculo.");
        }
        if (!"CO2e".equals(gas)) {
            throw invalid("Este pino aceita somente CO2e. CO2, CH4 e N2O não ocupam o lugar deste fator.");
        }
        if ("EF_FUEL".equals(kind)) {
            if (grid != null || !"GASOLINE".equals(fuel) || !"tCO2e/USgal".equals(unit)) {
                throw invalid("O fator fóssil não entra na emissão elétrica do projeto e não aceita unidade de rede.");
            }
            return;
        }
        if ("EF_GRID".equals(kind)) {
            if (fuel != null || !"SIN".equals(grid) || !"tCO2e/kWh".equals(unit)) {
                throw invalid("O fator elétrico não entra na linha de base fóssil e não aceita unidade de combustível.");
            }
            return;
        }
        throw invalid("Este pino de fonte aceita somente fator fóssil ou fator elétrico.");
    }

    static Scalar scalar(String kind, String raw, String rule, String evidence) {
        if (!"IR".equals(kind) && !"DY".equals(kind)) {
            throw invalid("IR e Dy são os únicos parâmetros declarados por regra, sem fonte de fator.");
        }
        if (rule == null || rule.strip().length() < 8 || rule.strip().length() > 300
                || evidence == null || evidence.strip().length() < 3 || evidence.strip().length() > 300) {
            throw invalid("IR e Dy exigem regra e localizador de prova. Sem isso o valor não é gravado.");
        }
        if (raw == null || raw.isBlank()) {
            throw invalid("Ausência não é zero nem 1. Não grave um pino sem o valor declarado.");
        }
        if (!raw.matches("\\d+(\\.\\d+)?")) {
            throw invalid("Informe o valor em decimal não negativo. A plataforma não escolhe 1.");
        }
        BigDecimal value = new BigDecimal(raw);
        if (value.signum() < 0 || value.precision() > 30 || value.scale() > 18) {
            throw invalid("Valor fora dos limites do contrato.");
        }
        if (value.signum() == 0) return new Scalar("DECLARED_ZERO", "0", false);
        String canonical = value.stripTrailingZeros().toPlainString();
        return new Scalar("DECLARED_EXPLICIT", canonical, value.compareTo(BigDecimal.ONE) == 0);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
