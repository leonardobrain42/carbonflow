package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenHourlyProjectEmissionsTest {
    private static final Instant START = Instant.parse("2027-02-01T00:00:00Z");

    @Test
    void weightsEveryHourByItsConsumptionAndKeepsCanonicalOrder() {
        var first = hour(0, "900", "1000", "0.0005");
        var second = hour(1, "1800", "2000", "0.0004");
        var memory = OpenHourlyProjectEmissions.calculate(series(List.of(second, first)));
        assertEquals("2700", memory.edKwh());
        assertEquals("3000", memory.ecKwh());
        assertEquals("1.3", memory.peTco2e());
        assertEquals("0.5", memory.hours().getFirst().peTco2e());
        assertEquals("0.8", memory.hours().getLast().peTco2e());
        assertEquals(memory, OpenHourlyProjectEmissions.calculate(series(List.of(first, second))));
        assertNotEquals("1.35", memory.peTco2e());
    }

    @Test
    void keepsTinyProductsAndExplicitZeroWithoutRoundingHours() {
        var memory = OpenHourlyProjectEmissions.calculate(series(List.of(
            hour(0, "0", "0", "0"),
            hour(1, "0.000000000001", "0.000000000001", "0.000000000000000001"))));
        assertEquals("0.000000000000000000000000000001", memory.peTco2e());
        assertEquals("0", memory.hours().getFirst().peTco2e());
    }

    @Test
    void rejectsGapsDuplicatesPartialWindowsAndMissingOperands() {
        assertThrows(IllegalArgumentException.class, () ->
            OpenHourlyProjectEmissions.calculate(series(List.of(hour(0, "1", "1", "0.1")))));
        assertThrows(IllegalArgumentException.class, () ->
            OpenHourlyProjectEmissions.calculate(series(List.of(
                hour(0, "1", "1", "0.1"), hour(0, "1", "1", "0.2")))));
        assertThrows(IllegalArgumentException.class, () ->
            OpenHourlyProjectEmissions.calculate(series(List.of(
                hour(0, "1", "1", "0.1"), hour(2, "1", "1", "0.2")))));
        assertThrows(IllegalArgumentException.class, () ->
            OpenHourlyProjectEmissions.calculate(series(Arrays.asList(null, hour(1, "1", "1", "0.1")))));
        assertThrows(IllegalArgumentException.class, () -> OpenHourlyProjectEmissions.calculate(
            new OpenHourlyProjectEmissions.Series(START.plusSeconds(1), START.plusSeconds(7200),
                "SIN", List.of(hour(0, "1", "1", "0.1")))));
        assertThrows(IllegalArgumentException.class, () -> OpenHourlyProjectEmissions.calculate(
            series(List.of(new OpenHourlyProjectEmissions.Hour(START, n("1"), n("1"), null),
                hour(1, "1", "1", "0.1")))));
    }

    @Test
    void rejectsOtherRegionsNegativeValuesAndInconsistentEnergy() {
        var hours = List.of(hour(0, "1", "1", "0.1"), hour(1, "1", "1", "0.1"));
        assertThrows(IllegalArgumentException.class, () -> OpenHourlyProjectEmissions.calculate(
            new OpenHourlyProjectEmissions.Series(START, START.plusSeconds(7200), "OTHER_GRID", hours)));
        assertThrows(IllegalArgumentException.class, () -> OpenHourlyProjectEmissions.calculate(
            series(List.of(hour(0, "1", "1", "-0.1"), hour(1, "1", "1", "0.1")))));
        assertThrows(IllegalArgumentException.class, () -> OpenHourlyProjectEmissions.calculate(
            series(List.of(hour(0, "2", "1", "0.1"), hour(1, "1", "1", "0.1")))));
    }

    @Test
    void kernelAndPersistedMemoryUseHourlyPeForTheNetReduction() {
        var series = series(List.of(hour(0, "900", "1000", "0.0005"), hour(1, "1800", "2000", "0.0004")));
        var input = input("3000", null);
        var decision = OpenE8RunDecision.decide(new OpenE8RunDecision.OperandSet(true, false, false,
            List.of(), input, series));
        assertEquals("4.05", decision.memory().beTco2e());
        assertEquals("1.3", decision.memory().peTco2e());
        assertEquals("2.75", decision.memory().erTco2e());
        assertFalse(decision.canIssueVcu());
        assertFalse(decision.memory().usableForE8());
        String json = OpenE8RunDecision.traceJson(decision.memory());
        var stored = OpenE8RunDecision.readTrace(json);
        assertEquals(decision.memory().trace(), stored.steps());
        assertEquals(decision.memory().hourlyPe(), stored.hourlyPe());
        // PostgreSQL jsonb can add whitespace and reorder properties.
        var old = OpenE8RunDecision.readTrace(
            "[{\"result\": \"1.5\", \"unit\": \"tCO2e\", \"code\": \"PE\", \"reference\": \"eq4\"}]");
        assertEquals("1.5", old.steps().getFirst().result());
        assertNull(old.hourlyPe());
    }

    @Test
    void rejectsTotalsFromAnotherWindowAndAmbiguousFactorRoute() {
        var series = series(List.of(hour(0, "900", "1000", "0.0005"), hour(1, "1800", "2000", "0.0004")));
        assertThrows(IllegalArgumentException.class, () ->
            OpenGridCalculationMath.calculate(input("3100", null), series));
        assertThrows(IllegalArgumentException.class, () ->
            OpenGridCalculationMath.calculate(input("3000", n("0.0005")), series));
    }

    private static OpenGridCalculationMath.Input input(String ec, BigDecimal factor) {
        return new OpenGridCalculationMath.Input(OpenGridCalculationMath.BRANCH, 1,
            List.of(new OpenGridCalculationMath.FleetRow("BEV", 20, n("20"), n("30"))),
            n("2700"), n(ec), n("0.009"), factor, n("1"),
            new OpenGridCalculationMath.Discount(n("0"), n("0"), true));
    }

    private static OpenHourlyProjectEmissions.Series series(List<OpenHourlyProjectEmissions.Hour> hours) {
        return new OpenHourlyProjectEmissions.Series(START, START.plusSeconds(7200), "SIN", hours);
    }

    private static OpenHourlyProjectEmissions.Hour hour(int offset, String ed, String ec, String factor) {
        return new OpenHourlyProjectEmissions.Hour(START.plusSeconds(offset * 3600L), n(ed), n(ec), n(factor));
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }
}
