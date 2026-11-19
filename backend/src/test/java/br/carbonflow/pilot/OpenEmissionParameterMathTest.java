package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

class OpenEmissionParameterMathTest {
    private static BigDecimal dec(String value) { return new BigDecimal(value); }

    @Test
    void gridTco2ePerMwhConvertsToPerKwhWithoutClaimingVt0011Admission() {
        var factor = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2e", "tCO2e/MWh", dec("0.559"));
        assertEquals("tCO2e/kWh", factor.normalizedUnit());
        assertEquals(dec("0.000559000000000000"), factor.normalizedValue());
        assertEquals("DIVIDE_BY_1000_KWH_PER_MWH", factor.formula());
        assertFalse(factor.usableForE8());
    }

    @Test
    void fuelKilogramsPerLitreUsesUsGallonOnlyAndDoesNotAssumeDensityOrGwp() {
        var factor = OpenEmissionParameterMath.convert(
            "EF_FUEL", "CO2e", "kgCO2e/L", dec("0.45"));
        assertEquals("tCO2e/USgal", factor.normalizedUnit());
        assertEquals(dec("0.001703435302800000"), factor.normalizedValue());
        assertEquals("KG_PER_L_TO_TONNE_PER_US_GALLON", factor.formula());
        assertFalse(factor.usableForE8());
        var methane = OpenEmissionParameterMath.convert(
            "EF_FUEL", "CH4", "kgCH4/L", dec("0.2"));
        assertEquals("tCH4/USgal", methane.normalizedUnit());
        assertEquals(dec("0.000757082356800000"), methane.normalizedValue());
        assertFalse(methane.usableForE8());
    }

    @Test
    void co2IsNotSilentlyPromotedToCo2Equivalent() {
        var co2 = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2", "tCO2/MWh", dec("0.10"));
        assertEquals("tCO2/kWh", co2.normalizedUnit());
        assertFalse(co2.usableForE8());
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2/MWh", dec("0.10")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_FUEL", "CO2e", "kgCO2/L", dec("2.1")));
    }

    @Test
    void rejectsImperialGallonsAndElectricityFuelCrossOver() {
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_FUEL", "CO2e", "tCO2e/imperial-gal", dec("1")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_FUEL", "CO2e", "tCO2e/MWh", dec("1")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2e/USgal", dec("1")));
    }

    @Test
    void irAndDyNeverReceiveAssumedOneOrAnyOtherDefault() {
        for (var kind : new String[]{"IR", "DY"}) {
            assertThrows(IllegalArgumentException.class, () ->
                OpenEmissionParameterMath.convert(kind, null, "fraction", dec("1")));
            assertThrows(IllegalArgumentException.class, () ->
                OpenEmissionParameterMath.convert(kind, null, "fraction", null));
        }
    }

    @Test
    void missingValueIsDifferentFromEvidenceBackedZero() {
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2e/MWh", null));
        var zeroCandidate = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2e", "tCO2e/MWh", BigDecimal.ZERO);
        assertEquals(0, zeroCandidate.normalizedValue().signum());
        assertFalse(zeroCandidate.usableForE8());
    }

    @Test
    void rejectsNegativeOverflowAndPrecisionLoss() {
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2e/MWh", dec("-0.1")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2e/MWh",
                dec("999999999999999999999999999999")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenEmissionParameterMath.convert("EF_GRID", "CO2e", "tCO2e/MWh",
                dec("0.0000000000001")));
        var tiny = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2e", "tCO2e/MWh", dec("0.000000000001"));
        assertEquals(dec("0.000000000000001000"), tiny.normalizedValue());
    }

    @Test
    void conversionIsDeterministicAndNeverAdmitsRealEmissionFactor() {
        var a = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2e", "kgCO2e/kWh", dec("0.120000"));
        var b = OpenEmissionParameterMath.convert(
            "EF_GRID", "CO2e", "kgCO2e/kWh", dec("0.120000"));
        assertEquals(a, b);
        assertEquals(dec("0.000120000000000000"), a.normalizedValue());
        assertFalse(a.usableForE8());
    }
}
