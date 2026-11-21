package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenE8RunDecisionTest {
    @Test
    void incompleteSetStaysBlockedAndKeepsTheEquationPlan() {
        var actor = UUID.randomUUID();
        var decision = OpenE8RunDecision.decide(ignored -> new OpenE8RunDecision.OperandSet(
            false, false, false, List.of(
                OpenE8RunDecision.gate("IR", "MISSING"),
                OpenE8RunDecision.gate("EF_FUEL", "MISSING"),
                OpenE8RunDecision.gate("ED", "MISSING")), null), actor);
        assertEquals("BLOCKED", decision.state());
        assertFalse(decision.canIssueVcu());
        assertNull(decision.memory());
        assertEquals(7, decision.equationPlan().size());
        assertEquals("BE", decision.equationPlan().get(2).outputCode());
        assertEquals("NOT_CALCULATED", decision.equationPlan().get(2).status());
        assertTrue(decision.equationPlan().get(2).blockedBy().contains("ED"));
        assertEquals("A melhoria tecnológica não tem valor normalizado armazenado. "
            + "A tentativa não inventa 1.", reason(decision, "IR"));
        assertEquals("Não há fator fóssil do projeto com valor armazenado.",
            reason(decision, "EF_FUEL"));
        assertEquals("Não há energia entregue vigente neste período.",
            reason(decision, "ED"));
        var hours = OpenE8RunDecision.gate("HOURLY_EFEL_NOT_UNIFORM", "REVIEW_REQUIRED");
        assertEquals("As horas do vínculo não compartilham um único fator elétrico. "
            + "O motor não calcula média.", hours.reason());
        assertEquals("Manter um único fator elétrico horário ou esperar o cálculo por hora",
            hours.nextAction());
    }

    @Test
    void sameActorDoesNotCallTheKernel() {
        var author = UUID.randomUUID();
        var decision = OpenE8RunDecision.decide(actor -> new OpenE8RunDecision.OperandSet(
            true, false, actor.equals(author),
            List.of(OpenE8RunDecision.gate("IR", "NOT_ADMITTED")),
            in("2700", "3000", "0.009", "0.0003", null,
                new OpenGridCalculationMath.Discount(n("0"), n("0"), true))), author);
        assertEquals("BLOCKED", decision.state());
        assertNull(decision.memory());
        assertTrue(decision.gates().stream().anyMatch(g -> "ACTOR_SEGREGATION".equals(g.code())));
    }

    @Test
    void staleCompleteSetDoesNotCallTheKernel() {
        var decision = OpenE8RunDecision.decide(ignored -> new OpenE8RunDecision.OperandSet(
            true, true, false, List.of(OpenE8RunDecision.gate("ED", "NOT_ADMITTED")),
            in("2700", "3000", "0.009", "0.0003", null,
                new OpenGridCalculationMath.Discount(n("0"), n("0"), true))), UUID.randomUUID());
        assertEquals("BLOCKED", decision.state());
        assertNull(decision.memory());
    }

    @Test
    void segregatedReaderRunsAnInternalRehearsalWithoutAdmission() {
        var author = UUID.randomUUID();
        var reviewer = UUID.randomUUID();
        var decision = OpenE8RunDecision.decide(actor -> new OpenE8RunDecision.OperandSet(
            true, false, actor.equals(author),
            List.of(OpenE8RunDecision.gate("NORMATIVE_PACK", "NOT_ADMITTED")),
            in("2700", "3000", "0.009", "0.0003", "1",
                new OpenGridCalculationMath.Discount(n("0"), n("0"), true))), reviewer);
        assertEquals("REVIEWED_INTERNAL", decision.state());
        assertFalse(decision.canIssueVcu());
        assertNotNull(decision.memory());
        assertFalse(decision.memory().usableForE8());
        assertEquals("PURE_MATH_TEST_ONLY_NOT_ADMITTED", decision.memory().kernelState());
        assertEquals("3.15", decision.memory().erTco2e());
        assertEquals("4.05", decision.memory().beTco2e());
        assertEquals("0.9", decision.memory().peTco2e());
        String trace = OpenE8RunDecision.traceJson(decision.memory());
        assertTrue(trace.contains("\"code\":\"ER\""));
        assertFalse(trace.contains("BEV-A"));
        assertEquals(decision.memory().trace(), OpenE8RunDecision.parseTrace(trace));
    }

    @Test
    void movedLinksAreReportedWithoutRewritingTheStoredState() {
        assertEquals("STALE", OpenE8RunDecision.responseState("BLOCKED", true));
        assertEquals("REVIEWED_INTERNAL",
            OpenE8RunDecision.responseState("REVIEWED_INTERNAL", false));
    }

    private static OpenGridCalculationMath.Input in(String ed, String ec, String fossil,
            String electricity, String ir, OpenGridCalculationMath.Discount discount) {
        return new OpenGridCalculationMath.Input(OpenGridCalculationMath.BRANCH, 1, List.of(
            new OpenGridCalculationMath.FleetRow("BEV-B", 8, n("23"), n("27")),
            new OpenGridCalculationMath.FleetRow("BEV-A", 12, n("18"), n("32"))),
            n(ed), n(ec), n(fossil), n(electricity), ir == null ? null : n(ir), discount);
    }

    private static BigDecimal n(String value) { return new BigDecimal(value); }

    private static String reason(OpenE8RunDecision.Decision decision, String code) {
        return decision.gates().stream().filter(gate -> code.equals(gate.code()))
            .findFirst().orElseThrow().reason();
    }
}
