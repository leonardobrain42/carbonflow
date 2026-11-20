package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import org.junit.jupiter.api.Test;

/**
 * Synthetic arithmetic oracle only. Never a published OM or an admissible
 * project grid factor. Tests do not authorize an E8 calculation.
 */
class OpenVt0011Case2MathTest {
    @Test
    void caseTwoWeightsOmAsOneWithoutTreatingBmFactorAsZero() {
        var preview=OpenVt0011Case2Math.illustrate(new BigDecimal("0.500"));
        assertEquals("1.00",preview.omWeight());
        assertEquals("0.00",preview.bmWeight());
        assertEquals("CASE_2_INCREASE_GRID_CONSUMPTION",preview.caseCode());
        assertEquals("tCO2/MWh",preview.originalUnit());
        assertEquals("tCO2/kWh",preview.convertedUnit());
        assertEquals(0,new BigDecimal("0.5").compareTo(
            new BigDecimal(preview.illustrativeCmTco2PerMwh())));
        assertEquals(0,new BigDecimal("0.0005").compareTo(
            new BigDecimal(preview.illustrativeCmTco2PerKwh())));
        assertFalse(preview.usableForE8());
        assertTrue(preview.qualification().contains("CO2 NÃO"));
        assertTrue(preview.qualification().contains("BM não presumida zero"));
    }

    @Test
    void explicitZeroIsNotInterpretedAsAbsentFactor() {
        var result=OpenVt0011Case2Math.illustrate(BigDecimal.ZERO);
        assertEquals(0,new BigDecimal(result.illustrativeCmTco2PerKwh()).signum());
        assertFalse(result.usableForE8());
    }

    @Test
    void rejectsNegativeMissingAndTooPreciseOm() {
        assertThrows(IllegalArgumentException.class,
            ()->OpenVt0011Case2Math.illustrate(null));
        assertThrows(IllegalArgumentException.class,
            ()->OpenVt0011Case2Math.illustrate(new BigDecimal("-0.5")));
        assertThrows(IllegalArgumentException.class,
            ()->OpenVt0011Case2Math.illustrate(new BigDecimal("0.1234567890123")));
        assertThrows(IllegalArgumentException.class,
            ()->OpenVt0011Case2Math.illustrate(new BigDecimal("0.000000000001")));
    }
}
