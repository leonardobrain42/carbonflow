package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Map;
import java.util.Set;

/**
 * Dimensional conversion only for CF-OPEN-007 / #96.
 *
 * This class is not a source-admission engine, a VT0011 implementation or an
 * E8 calculation adapter. An arithmetically valid value is not an admissible
 * Brazilian emission factor. Never substitute missing IR or Dy with 1.
 */
final class OpenEmissionParameterMath {
    static final int SCALE = 18;
    static final BigDecimal LITRES_PER_US_GALLON = new BigDecimal("3.785411784");
    static final BigDecimal KILOGRAMS_PER_TONNE = new BigDecimal("1000");
    static final BigDecimal KWH_PER_MWH = new BigDecimal("1000");

    record Preview(String kind, String gas, String originalUnit, BigDecimal originalValue,
                   String normalizedUnit, BigDecimal normalizedValue,
                   String formula, boolean usableForE8) {}

    private static final Set<String> GASES = Set.of("CO2", "CO2e", "CH4", "N2O");
    private static final Map<String, String> GRID_UNITS = Map.of(
        "CO2", "tCO2", "CO2e", "tCO2e", "CH4", "tCH4", "N2O", "tN2O"
    );

    private OpenEmissionParameterMath() {}

    static Preview convert(String kind, String gas, String originalUnit, BigDecimal originalValue) {
        return convert(kind, gas, originalUnit, originalValue, 12);
    }

    /** {@code maxScale} widens only the accepted decimals of the ORIGINAL value (kept literal), e.g. the 16 decimals the MCTI publishes. */
    static Preview convert(String kind, String gas, String originalUnit, BigDecimal originalValue, int maxScale) {
        if (kind == null || !Set.of("EF_GRID", "EF_FUEL", "IR", "DY").contains(kind)) {
            throw invalid("Tipo de parâmetro desconhecido.");
        }
        if ("IR".equals(kind) || "DY".equals(kind)) {
            // The mathematical and temporal meaning of IR and Dy is methodology-specific.
            // Reject even an explicit '1' until their normative rule and evidence are reviewed.
            throw invalid("IR e Dy exigem regra, período e evidência específicos; não possuem default.");
        }
        if (gas == null || !GASES.contains(gas)) {
            throw invalid("Gás obrigatório: CO2, CO2e, CH4 ou N2O.");
        }
        if (originalUnit == null || originalUnit.isBlank() || originalValue == null) {
            throw invalid("Valor ausente não equivale a zero; informe valor e unidade.");
        }
        if (originalValue.signum() < 0 || originalValue.precision() > Math.max(30, 12 + maxScale) ||
                originalValue.scale() > maxScale || originalValue.precision() - originalValue.scale() > 12) {
            throw invalid("Valor fora dos limites decimais do contrato.");
        }

        String numerator = GRID_UNITS.get(gas);
        String target = numerator + ("EF_GRID".equals(kind) ? "/kWh" : "/USgal");
        BigDecimal normalized;
        String formula;
        if ("EF_GRID".equals(kind)) {
            if (originalUnit.equals(numerator + "/MWh")) {
                normalized = originalValue.divide(KWH_PER_MWH, SCALE, RoundingMode.HALF_EVEN);
                formula = "DIVIDE_BY_1000_KWH_PER_MWH";
            } else if (originalUnit.equals(numerator + "/kWh")) {
                normalized = originalValue.setScale(SCALE, RoundingMode.HALF_EVEN);
                formula = "IDENTITY";
            } else if (originalUnit.equals("kg" + gas + "/kWh")) {
                normalized = originalValue.divide(KILOGRAMS_PER_TONNE, SCALE, RoundingMode.HALF_EVEN);
                formula = "KG_TO_TONNE";
            } else {
                throw invalid("Unidade elétrica incompatível com gás declarado; MCTI não é VT0011 automaticamente.");
            }
        } else {
            if (originalUnit.equals(numerator + "/USgal")) {
                normalized = originalValue.setScale(SCALE, RoundingMode.HALF_EVEN);
                formula = "IDENTITY_US_GALLON";
            } else if (originalUnit.equals(numerator + "/L")) {
                normalized = originalValue.multiply(LITRES_PER_US_GALLON)
                    .setScale(SCALE, RoundingMode.HALF_EVEN);
                formula = "L_TO_US_GALLON";
            } else if (originalUnit.equals("kg" + gas + "/L")) {
                normalized = originalValue.multiply(LITRES_PER_US_GALLON)
                    .divide(KILOGRAMS_PER_TONNE, SCALE, RoundingMode.HALF_EVEN);
                formula = "KG_PER_L_TO_TONNE_PER_US_GALLON";
            } else {
                throw invalid("Unidade fóssil incompatível; galão imperial, densidade e GWP não são presumidos.");
            }
        }

        // A tiny positive value must not silently round to zero.
        if (normalized.signum() == 0 && originalValue.signum() > 0) {
            throw invalid("Precisão insuficiente para normalizar este fator sem perder valor.");
        }
        if (normalized.precision() > 30) throw invalid("Valor normalizado excede NUMERIC(30,18).");
        return new Preview(kind, gas, originalUnit, originalValue, target, normalized,
                           formula, false);
    }

    private static IllegalArgumentException invalid(String detail) {
        return new IllegalArgumentException(detail);
    }
}
