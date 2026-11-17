package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

class CarbonCalculatorTest {
    private final Fixture valid = new FixtureCatalog(new ObjectMapper().findAndRegisterModules())
            .load(FixtureCatalog.DEFAULT).fixture();

    @Test
    void rejectsZeroFleetAndMissingDiscountProof() {
        var calculation = valid.calculation();
        var zeroFleet = new Fixture.Calculation(calculation.zone(), calculation.factor(), calculation.replacementCandidate(),
                calculation.fuelFactorTco2PerGal(), calculation.ir(), List.of(new Fixture.Model(0, "18", "32")),
                calculation.periods());
        assertCode("EVR_TOTAL_INVALIDO", () -> new CarbonCalculator().calculate(with(valid.method(), zeroFleet)));
        var method = valid.method();
        var missingProof = new Fixture.Method(method.program(), method.ruleSet(), method.documentModel(),
                method.packageVersion(), method.dependencies(), method.profile(), null);
        assertCode("PROVA_INSUFICIENTE", () -> new CarbonCalculator().calculate(with(missingProof, calculation)));
    }

    @Test
    void candidateCannotReplacePinnedFactor() {
        var input = valid.calculation();
        var replacement = new Fixture.Calculation(input.zone(), input.replacementCandidate(),
                null, input.fuelFactorTco2PerGal(), input.ir(), input.models(), input.periods());
        assertCode("FONTE_NAO_ADMISSIVEL", () -> new CarbonCalculator().calculate(with(valid.method(), replacement)));
    }

    @Test
    void assignsBrasiliaBoundariesOnceAndRejectsUnsegmentedReadings() {
        var windows = new ArrayList<VintageWindows.Window>();
        for (Fixture.Period period : valid.calculation().periods()) {
            windows.addAll(VintageWindows.split(new VintageWindows.Window(period.start(), period.endExclusive())));
        }
        List<String> limits = List.of("2028-01-01T03:00:00Z", "2028-10-01T03:00:00Z", "2029-01-01T03:00:00Z");
        for (int index = 0; index < limits.size(); index++) {
            var boundary = OffsetDateTime.parse(limits.get(index));
            var before = boundary.minusHours(2);
            var after = boundary.plusHours(1);
            assertEquals(windows.get(index), VintageWindows.assign(before, boundary, windows, "cut"));
            assertEquals(windows.get(index + 1), VintageWindows.assign(boundary, after, windows, "cut"));
            assertCode("LEITURA_SEM_SEGMENTACAO", () -> VintageWindows.assign(before, after, windows, "cut"));
            assertCode("INTERVALO_INVALIDO", () -> VintageWindows.assign(after, before, windows, "cut"));
        }
        assertThrows(DateTimeParseException.class, () -> OffsetDateTime.parse("2028-10-01T00:00:00"));
    }

    @Test
    void m2OpensOnlyWithinItsWindowWithOperationProof() {
        var window = new VintageWindows.Window(LocalDate.parse("2028-10-01"), LocalDate.parse("2029-10-01"));
        var operation = new Fixture.Reference("SIM-OPERACAO-M2", 1);
        var openingUtc = window.opening().withOffsetSameInstant(ZoneOffset.UTC);
        assertEquals("PREVISTO", PilotScenario.collectionAt(window, openingUtc.minusSeconds(1), operation));
        assertEquals("PREVISTO", PilotScenario.collectionAt(window, openingUtc, null));
        assertEquals("COLETANDO", PilotScenario.collectionAt(window, openingUtc, operation));
        assertEquals("EM_CONFERENCIA", PilotScenario.collectionAt(window, window.ending(), operation));
    }

    private Fixture with(Fixture.Method method, Fixture.Calculation calculation) {
        return new Fixture(valid.contractVersion(), valid.environment(), valid.projectId(), method, calculation,
                valid.issuanceStatus());
    }

    private void assertCode(String code, Runnable run) {
        assertEquals(code, assertThrows(PilotProblem.class, run::run).code);
    }
}
