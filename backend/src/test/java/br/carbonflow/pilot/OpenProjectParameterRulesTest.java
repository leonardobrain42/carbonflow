package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenProjectParameterRulesTest {
    @Test
    void absenceZeroAndOneStayDifferentAndFactorsDoNotSwapPlaces() {
        var missing = assertThrows(IllegalArgumentException.class,
            () -> OpenProjectParameterRules.scalar("IR", "  ", "VM0038 fleet rule", "proof-1"));
        assertTrue(missing.getMessage().contains("Ausência"));
        var withoutRule = assertThrows(IllegalArgumentException.class,
            () -> OpenProjectParameterRules.scalar("DY", "1", "curta", "prova"));
        assertTrue(withoutRule.getMessage().contains("regra"));

        var zero = OpenProjectParameterRules.scalar("DY", "0.0",
            "VM0038 double-claim review", "contrato simulado");
        assertEquals("DECLARED_ZERO", zero.valueKind());
        assertEquals("0", zero.canonical());
        assertFalse(zero.explicitOne());

        var one = OpenProjectParameterRules.scalar("IR", "1",
            "VM0038 technology improvement", "memoria da frota");
        assertEquals("DECLARED_EXPLICIT", one.valueKind());
        assertTrue(one.explicitOne());
        assertEquals("1", one.canonical());

        var other = OpenProjectParameterRules.scalar("IR", "0.80",
            "VM0038 technology improvement", "memoria da frota");
        assertEquals("0.8", other.canonical());
        assertFalse(other.explicitOne());

        assertThrows(IllegalArgumentException.class, () -> OpenProjectParameterRules.factor(
            "EF_FUEL", 2036, 2036, "CO2e", "GASOLINE", null, "tCO2e/kWh"));
        assertThrows(IllegalArgumentException.class, () -> OpenProjectParameterRules.factor(
            "EF_GRID", 2036, 2036, "CO2e", null, "SIN", "tCO2e/USgal"));
        assertThrows(IllegalArgumentException.class, () -> OpenProjectParameterRules.factor(
            "EF_GRID", 2036, 2035, "CO2e", null, "SIN", "tCO2e/kWh"));
        assertThrows(IllegalArgumentException.class, () -> OpenProjectParameterRules.factor(
            "EF_FUEL", 2036, 2036, "CO2", "GASOLINE", null, "tCO2e/USgal"));
        OpenProjectParameterRules.factor("EF_FUEL", 2036, 2036, "CO2e", "GASOLINE", null, "tCO2e/USgal");
        OpenProjectParameterRules.factor("EF_GRID", 2036, 2036, "CO2e", null, "SIN", "tCO2e/kWh");
    }
}
