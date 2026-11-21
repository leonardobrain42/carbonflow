package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

class ProjectRequirementProjectionTest {
    @Test
    void sheetMessagesAreNotEternalClientTasksAndOnlyIncidentGatesMove() {
        var missing = project(true, true, disclosureMissing(), preflight(false));
        assertEquals("NEEDS_EVIDENCE", item(missing, "DRIVER_DISCLOSURE").state());
        assertEquals("MINE", item(missing, "DRIVER_DISCLOSURE").group());
        assertTrue(item(missing, "DRIVER_DISCLOSURE").clientAction());
        for (String code : List.of("VMD0049_ROUTE", "MARKET_ROUTE", "REGULATORY_SURPLUS")) {
            var item = item(missing, code);
            assertEquals("AWAITING_NORMATIVE_EVALUATION", item.state());
            assertNotEquals("MINE", item.group());
            assertFalse(item.clientAction());
            assertNull(item.destination());
            assertFalse(item.admitsE8());
        }
        var energy = item(missing, "ED_EC");
        assertEquals("NOT_INCIDENT", energy.state());
        assertFalse(energy.blocksExAntePd());
        assertFalse(energy.clientAction());
        assertFalse(missing.exAnteRequiresMeasuredEnergy());
        assertEquals(1, missing.requirements().stream().filter(i -> "ED_EC".equals(i.requirementId())).count());

        var declared = project(true, true, disclosurePresent(), preflight(false));
        assertTrue(declared.requirements().stream().noneMatch(i -> "DRIVER_DISCLOSURE".equals(i.requirementId())));
        assertTrue(declared.requirements().stream().noneMatch(i -> "OWNER_CONTRACT".equals(i.requirementId())));
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(declared, "REGULATORY_SURPLUS").state());
        assertFalse(item(declared, "REGULATORY_SURPLUS").clientAction());
        assertTrue(declared.requirements().stream().noneMatch(i ->
            "APPROVED".equals(i.state()) || "COMPLETED".equals(i.state()) || i.admitsE8()));
    }

    @Test
    void anotherMethodologyDoesNotReceiveOpenRequirements() {
        var foreign = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "ACM0002", true, true, true, true, true, true,
            disclosureMissing(), preflight(false), ProjectRequirementProjection.Owners.NONE));
        assertFalse(foreign.openBranch());
        assertEquals(1, foreign.requirements().size());
        assertEquals("METHODOLOGY_BRANCH", foreign.requirements().get(0).requirementId());
        assertEquals("OUT_OF_SCOPE", foreign.requirements().get(0).state());
        assertTrue(foreign.requirements().get(0).reason().contains("não usa os requisitos OPEN/DCFC"));
        assertFalse(foreign.canRunE8());
    }

    @Test
    void dateGapAsksForRecheckAndTriageChangeStalesOnlyThatGate() {
        var gap = OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-001", "ON_CHARGER",
            "PHOTO", LocalDate.parse("2027-03-01"), "NO_OVERLAP_DECLARED",
            LocalDate.parse("2027-02-01"), null), false);
        var projected = project(true, true, gap, preflight(false));
        assertEquals("RECHECK_REQUIRED", item(projected, "RIGHTS_DATE_GAP").state());
        assertEquals("AWAITING_REVIEW", item(projected, "RIGHTS_DATE_GAP").group());

        var stale = project(true, true, disclosureMissing(true), preflight(true));
        assertEquals("STALE", item(stale, "TRIAGE_CHANGED").state());
        assertEquals("MINE", item(stale, "TRIAGE_CHANGED").group());
        assertEquals("NEEDS_EVIDENCE", item(stale, "DRIVER_DISCLOSURE").state());
        assertEquals("NOT_ADMITTED", item(stale, "EVR").state());
        assertNotEquals("STALE", item(stale, "EVR").state());
    }

    @Test
    void obtainedSourceAndCompleteDeclarationNeverAdmitE8OrAssignAPerson() {
        var forged = new OpenCalculationPreflight.Result("p", "SANDBOX", "engine", "VM0038",
            OpenCalculationPreflight.SCENARIO, "REQUEST_DATA", "BLOCKED_MISSING_DATA",
            true, true, true, new OpenCalculationPreflight.Emissions("0", "0", "0", "0", "0"),
            List.of(new OpenCalculationPreflight.Gate("EVR", "READY_FOR_HANDOFF",
                "Fonte marcada como pronta no catálogo.", "Vincular", "PREP"),
                new OpenCalculationPreflight.Gate("ED", "PENDING_MEASUREMENT", "sem medição", "medir", "OP"),
                new OpenCalculationPreflight.Gate("EC", "PENDING_MEASUREMENT", "sem medição", "medir", "OP")),
            List.of());
        var view = project(true, true, disclosurePresent(), forged);
        var evr = item(view, "EVR");
        assertEquals("NOT_ADMITTED", evr.state());
        assertFalse(evr.admitsE8());
        assertFalse(evr.clientAction());
        assertEquals("equipe CarbonFlow", evr.ownerRole());
        assertNull(evr.destination());
        assertFalse(view.canRunE8());
        assertFalse(view.canIssueVcu());
        assertFalse(view.toString().contains("SIM-"));
        assertTrue(view.requirements().stream().noneMatch(i -> i.ownerRole().contains("@")));
        assertTrue(view.limits().contains("Nenhum BE"));
    }

    @Test
    void calculationGrantDoesNotReceiveApplicabilityDetailOrMeasuredEnergyDuty() {
        var hidden = project(false, true, disclosureMissing(), preflight(false));
        assertTrue(hidden.requirements().stream().noneMatch(i ->
            "DRIVER_DISCLOSURE".equals(i.requirementId()) || "VMD0049_ROUTE".equals(i.requirementId())));
        assertFalse(hidden.requirements().toString().contains("Titular secreto"));
        assertEquals("NOT_INCIDENT", item(hidden, "ED_EC").state());
        assertFalse(item(hidden, "ED_EC").blocksExAntePd());
        var closed = project(false, false, disclosureMissing(), preflight(false));
        assertFalse(closed.diagnosticVisible());
        assertTrue(closed.requirements().isEmpty());
    }

    @Test
    void changedProofAsksForRecheckAndPreparedSourceStaysUnadmitted() {
        var recheck = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosurePresent(), preflight(false),
            new ProjectRequirementProjection.Owners(true, "FLEET_SELECTION_MISSING", "BINDING_MISSING")));
        assertEquals("RECHECK_REQUIRED", item(recheck, "RIGHTS_DEPENDENCY").state());
        assertEquals("AWAITING_REVIEW", item(recheck, "RIGHTS_DEPENDENCY").group());
        assertFalse(item(recheck, "RIGHTS_DEPENDENCY").clientAction());
        assertFalse(item(recheck, "RIGHTS_DEPENDENCY").admitsE8());
        assertEquals("AWAITING_NORMATIVE_EVALUATION", item(recheck, "REGULATORY_SURPLUS").state());
        assertEquals("NOT_ADMITTED", item(recheck, "EVR").state());

        var gap = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosureMissing(), preflight(false),
            new ProjectRequirementProjection.Owners(true, "PREPARATORY_NOT_ADMITTED", "CANDIDATE_NOT_ADMITTED")));
        assertEquals("NEEDS_EVIDENCE", item(gap, "DRIVER_DISCLOSURE").state());
        assertTrue(gap.requirements().stream().noneMatch(i -> "RIGHTS_DEPENDENCY".equals(i.requirementId())));
        assertEquals("SOURCE_CANDIDATE", item(gap, "EVR").state());
        assertEquals("SOURCE_CANDIDATE", item(gap, "EF_FUEL").state());
        assertFalse(item(gap, "EVR").admitsE8());
        assertEquals("NOT_ADMITTED", item(gap, "IR").state());
        assertEquals("NOT_INCIDENT", item(gap, "ED_EC").state());

        var staleFleet = ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", true, true, true, true, true, false,
            disclosurePresent(), preflight(false),
            new ProjectRequirementProjection.Owners(false, "STALE_NOT_ADMITTED", "BINDING_MISSING")));
        assertEquals("STALE", item(staleFleet, "EVR").state());
        assertEquals("STALE", item(staleFleet, "AFEC_MPG").state());
        assertEquals("NOT_ADMITTED", item(staleFleet, "EF_FUEL").state());
        assertEquals("NOT_ADMITTED", item(staleFleet, "IR").state());
        assertFalse(staleFleet.canRunE8());
    }

    private static ProjectRequirementProjection.View project(boolean applicability, boolean calculation,
            OpenApplicability.Readiness readiness, OpenCalculationPreflight.Result preflight) {
        return ProjectRequirementProjection.project(new ProjectRequirementProjection.Input(
            "p", "VM0038", applicability, calculation, applicability, true, calculation, false,
            applicability ? readiness : null, calculation ? preflight : null,
            ProjectRequirementProjection.Owners.NONE));
    }

    private static ProjectRequirementProjection.Item item(ProjectRequirementProjection.View view, String id) {
        return view.requirements().stream().filter(i -> id.equals(i.requirementId())).findFirst().orElseThrow();
    }

    private static OpenApplicability.Readiness disclosureMissing() {
        return disclosureMissing(false);
    }

    private static OpenApplicability.Readiness disclosureMissing(boolean stale) {
        return OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular secreto", "CONTRACT-001",
            "NOT_DECLARED", null, null, "UNKNOWN", LocalDate.parse("2027-02-01"), null), stale);
    }

    private static OpenApplicability.Readiness disclosurePresent() {
        return OpenApplicability.evaluate(new OpenApplicability.Input(true, false, true,
            "DCFC", "BEV", "SIN", false, false, "Titular", "CONTRACT-001", "ON_CHARGER",
            "PHOTO-2027", LocalDate.parse("2027-01-15"), "NO_OVERLAP_DECLARED",
            LocalDate.parse("2027-02-01"), null), false);
    }

    private static OpenCalculationPreflight.Result preflight(boolean stale) {
        return OpenCalculationPreflight.evaluate(new OpenCalculationPreflight.Context(
            "p", "SANDBOX", "VM0038", "BR", "LDV", stale ? 2 : 1,
            new OpenCalculationPreflight.Applicability(1, 1, true, false, "DCFC", "BEV", "SIN", false, false)));
    }
}
