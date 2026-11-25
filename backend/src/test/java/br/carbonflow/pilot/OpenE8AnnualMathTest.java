package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenE8AnnualMathTest {
    private static final OpenIrIntraYearReuse.Canonical CANONICAL = new OpenIrIntraYearReuse.Canonical(
        UUID.fromString("00000000-0000-4000-8000-0000000000f1"), UUID.fromString("00000000-0000-4000-8000-0000000000f2"),
        UUID.fromString("00000000-0000-4000-8000-0000000000f3"), "COMBINED");
    private static final Instant START = Instant.parse("2027-02-01T03:00:00Z");
    private static final Instant MID = Instant.parse("2027-08-01T03:00:00Z");
    private static final Instant END = Instant.parse("2028-02-01T03:00:00Z");
    private static final OpenE8AnnualMath.IrReference IR =
        new OpenE8AnnualMath.IrReference(UUID.fromString("00000000-0000-0000-0000-0000000000a1"), 1, "a".repeat(64),
            OpenE8IrContext.CHECKED);

    @Test
    void sumsEachWindowErWithItsOwnDiscountAndStableReferences() {
        var first = member("M1", START, MID, "8", "3", "0", "2.5");
        var second = member("M2", MID, END, "4", "1", "0", "3");
        var result = annual(List.of(second, first));
        assertEquals("REVIEWED_INTERNAL", result.state());
        assertEquals("12", result.totals().beTco2e());
        assertEquals("4", result.totals().peTco2e());
        assertEquals("0", result.totals().leTco2e());
        assertEquals("5.5", result.totals().erTco2e());
        assertEquals("5.50", result.totals().erDisplay());
        assertTrue(result.issues().isEmpty());
        assertFalse(result.usableForE8());
        assertFalse(result.canIssueVcu());
        assertEquals(result, annual(List.of(first, second)));
        assertEquals(first.runId(), result.members().getFirst().runId());
    }

    @Test
    void roundsOnlyAfterAddingExactErAndAllowsNegativeReduction() {
        var a = member("M1", START, MID, "1", "0.995", "0", "0.005");
        var b = member("M2", MID, END, "1", "0.995", "0", "0.005");
        assertEquals("0.01", annual(List.of(a, b)).totals().erDisplay());
        var negative = member("M2", MID, END, "0", "1", "0", "-1");
        assertEquals("-0.995", annual(List.of(a, negative)).totals().erTco2e());
        assertEquals("-1.00", annual(List.of(a, negative)).totals().erDisplay());
    }

    @Test
    void reportsExactGapsIncludingBeginningAndEndInsteadOfPartialTotals() {
        var a = member("M1", START.plusSeconds(3600), MID, "1", "0", "0", "1");
        var b = member("M2", MID.plusSeconds(3600), END.minusSeconds(3600), "1", "0", "0", "1");
        var result = annual(List.of(a, b));
        assertNull(result.totals());
        var gaps = result.issues().stream().filter(i -> i.code().equals("COVERAGE_GAP")).toList();
        assertEquals(3, gaps.size());
        assertEquals(START, gaps.getFirst().from());
        assertEquals(START.plusSeconds(3600), gaps.getFirst().until());
        assertEquals(END, gaps.getLast().until());
        assertEquals("BLOCKED", result.state());
    }

    @Test
    void rejectsOverlapIncludingNestedWindowsAndDuplicateRevisions() {
        var full = member("M1", START, END, "1", "0", "0", "1");
        var nested = member("M2", MID, MID.plusSeconds(3600), "1", "0", "0", "1");
        var result = annual(List.of(full, nested));
        assertNull(result.totals());
        assertTrue(has(result, "COVERAGE_OVERLAP"));
        assertFalse(has(result, "COVERAGE_GAP"));
        var duplicate = annual(List.of(full, full));
        assertNull(duplicate.totals());
        assertTrue(has(duplicate, "DUPLICATE_PERIOD"));
    }

    @Test
    void includesOnlyIntersectingHalfOpenWindowsOfTheRequestedYear() {
        var full = member("M1", START, END, "1", "0", "0", "1");
        var before = member("OLD", START.minusSeconds(3600), START, "9", "0", "0", "9");
        var after = member("NEXT", END, END.plusSeconds(3600), "9", "0", "0", "9");
        var result = annual(List.of(full, before, after));
        assertEquals("1", result.totals().beTco2e());
        assertEquals(1, result.members().size());
        assertEquals(full, result.members().getFirst());
    }

    @Test
    void blockedStaleUnknownAndWrongYearNeverBecomeAnAnnualZero() {
        var full = member("M1", START, END, "1", "0", "0", "1");
        var stale = new OpenE8AnnualMath.Member(full.period(), full.runId(), 1, "stale",
            START, END, 1, "STALE", true, full.totals(), IR);
        assertTrue(has(annual(List.of(stale)), "RUN_STALE"));
        assertNull(annual(List.of(stale)).totals());
        var blocked = new OpenE8AnnualMath.Member("M1", UUID.randomUUID(), 2, "new",
            START, END, 1, "BLOCKED", false, null, IR);
        assertTrue(has(annual(List.of(blocked)), "RUN_NOT_CALCULATED"));
        assertNull(annual(List.of(blocked)).totals());
        var unknown = new OpenE8AnnualMath.Member("M2", null, 0, null,
            null, null, null, "MISSING", false, null, null);
        assertTrue(has(annual(List.of(full, unknown)), "UNLOCATED_PERIOD"));
        assertNull(annual(List.of(full, unknown)).totals());
        var wrong = new OpenE8AnnualMath.Member("M1", full.runId(), 1, "wrong",
            START, END, 2, "REVIEWED_INTERNAL", false, full.totals(), IR);
        assertTrue(has(annual(List.of(wrong)), "RUN_YEAR_MISMATCH"));
        assertNull(annual(List.of(wrong)).totals());
        assertTrue(has(annual(List.of()), "COVERAGE_GAP"));
    }

    @Test
    void rejectsMissingAttemptBoundaryCrossingAndInvalidMemory() {
        var missing = new OpenE8AnnualMath.Member("M1", null, 0, null,
            START, END, null, "MISSING", false, null, null);
        assertTrue(has(annual(List.of(missing)), "RUN_MISSING"));
        var crossing = member("M1", START.minusSeconds(1), END, "1", "0", "0", "1");
        assertTrue(has(annual(List.of(crossing)), "WINDOW_OUTSIDE_YEAR"));
        assertNull(annual(List.of(crossing)).totals());
        var invalid = member("M1", START, END, "1e10", "0", "0", "1");
        assertTrue(has(annual(List.of(invalid)), "RUN_MEMORY_INVALID"));
        assertNull(annual(List.of(invalid)).totals());
        var result = OpenE8AnnualMath.consolidate("project", 1, null, null,
            "CREDIT_START_RECHECK", List.of());
        assertTrue(has(result, "CREDIT_START_RECHECK"));
        assertNull(result.totals());
    }

    @Test
    void sourceRevisionChangesManifestWithoutMutatingOldConsolidation() {
        var first = member("M1", START, END, "1", "0", "0", "1");
        var old = annual(List.of(first));
        var revised = new OpenE8AnnualMath.Member("M1", UUID.randomUUID(), 2, "new-source-hash",
            START, END, 1, "REVIEWED_INTERNAL", false, first.totals(), IR);
        assertNotEquals(old.manifestSha256(), annual(List.of(revised)).manifestSha256());
        assertEquals(old, annual(List.of(first)));
    }

    @Test
    void sameYearRequiresTheSameIrAssessmentInEveryCalculatedWindow() {
        var first = member("M1", START, MID, "8", "3", "0", "2.5");
        var second = member("M2", MID, END, "4", "1", "0", "3");
        assertTrue(annual(List.of(first, second)).issues().isEmpty());
        var otherVersion = withIr(second, new OpenE8AnnualMath.IrReference(IR.assessmentId(), 2, IR.assessmentSha256(), IR.state()));
        var otherHash = withIr(second, new OpenE8AnnualMath.IrReference(IR.assessmentId(), 1, "b".repeat(64), IR.state()));
        var otherId = withIr(second, new OpenE8AnnualMath.IrReference(UUID.randomUUID(), 1, IR.assessmentSha256(), IR.state()));
        for (var different : List.of(otherVersion, otherHash, otherId)) {
            var result = annual(List.of(first, different));
            assertTrue(has(result, "IR_REFERENCE_MISMATCH"));
            assertNull(result.totals());
            assertEquals("BLOCKED", result.state());
        }
    }

    @Test
    void calculatedWindowWithoutRecordedIrNeverBecomesAnAnnualTotal() {
        var first = member("M1", START, MID, "8", "3", "0", "2.5");
        for (var missing : List.of(withIr(member("M2", MID, END, "4", "1", "0", "3"), null),
                withIr(member("M2", MID, END, "4", "1", "0", "3"),
                    new OpenE8AnnualMath.IrReference(IR.assessmentId(), null, IR.assessmentSha256(), IR.state())))) {
            var result = annual(List.of(first, missing));
            assertTrue(has(result, "IR_REFERENCE_MISSING"));
            assertNull(result.totals());
        }
    }

    @Test
    void irReferenceChangesTheManifestAndUncalculatedWindowsDoNotAddMismatch() {
        var first = member("M1", START, END, "1", "0", "0", "1");
        var other = withIr(first, new OpenE8AnnualMath.IrReference(IR.assessmentId(), 2, IR.assessmentSha256(), IR.state()));
        assertNotEquals(annual(List.of(first)).manifestSha256(), annual(List.of(other)).manifestSha256());
        var blocked = new OpenE8AnnualMath.Member("M1", UUID.randomUUID(), 1, "h", START, END, 1, "BLOCKED", false, null,
            new OpenE8AnnualMath.IrReference(UUID.randomUUID(), 9, "c".repeat(64), IR.state()));
        var result = annual(List.of(blocked));
        assertTrue(has(result, "RUN_NOT_CALCULATED"));
        assertFalse(has(result, "IR_REFERENCE_MISMATCH"));
    }

    @Test
    void firstYearIrBasisMakesTheAnnualFigureAnExAnteEstimateNeverAMonitoredResult() {
        var ex = new OpenE8AnnualMath.IrReference(IR.assessmentId(), 1, IR.assessmentSha256(),
            OpenE8IrContext.FIRST_YEAR_EX_ANTE);
        var estimate = annual(List.of(withIr(member("M1", START, END, "1", "0", "0", "1"), ex)));
        assertEquals("EX_ANTE_ESTIMATE", estimate.calculationBasis());
        assertEquals("REVIEWED_INTERNAL", estimate.state());
        assertFalse(estimate.usableForE8());
        assertFalse(estimate.canIssueVcu());
        var rehearsal = annual(List.of(member("M1", START, END, "1", "0", "0", "1")));
        assertEquals("INTERNAL_REHEARSAL", rehearsal.calculationBasis());
        assertNotEquals(estimate.manifestSha256(), rehearsal.manifestSha256());
        var noState = new OpenE8AnnualMath.IrReference(IR.assessmentId(), 1, IR.assessmentSha256(), null);
        assertTrue(has(annual(List.of(withIr(member("M1", START, END, "1", "0", "0", "1"), noState))), "IR_REFERENCE_MISSING"));
    }

    @Test
    void sameYearRequiresTheSameFleetRatingsCycleAndFuelInEveryCalculatedWindow() {
        var mid = START.plusSeconds((END.getEpochSecond() - START.getEpochSecond()) / 2);
        var first = member("M1", START, mid, "1", "0", "0", "1");
        var second = member("M2", mid, END, "1", "0", "0", "1");
        var same = annual(List.of(first, second));
        assertEquals("REVIEWED_INTERNAL", same.state());
        assertFalse(has(same, "FLEET_REFERENCE_MISMATCH") || has(same, "FUEL_REFERENCE_MISMATCH"));

        var otherFleet = new OpenIrIntraYearReuse.Canonical(UUID.randomUUID(), CANONICAL.ratingSnapshot(),
            CANONICAL.fuelCandidate(), CANONICAL.cycle());
        var otherRating = new OpenIrIntraYearReuse.Canonical(CANONICAL.fleetSnapshot(), UUID.randomUUID(),
            CANONICAL.fuelCandidate(), CANONICAL.cycle());
        var otherCycle = new OpenIrIntraYearReuse.Canonical(CANONICAL.fleetSnapshot(), CANONICAL.ratingSnapshot(),
            CANONICAL.fuelCandidate(), "URBAN");
        for (var changed : List.of(otherFleet, otherRating, otherCycle)) {
            var result = annual(List.of(first, withCanonical(second, changed)));
            assertTrue(has(result, "FLEET_REFERENCE_MISMATCH"));
            assertNull(result.totals());
            assertEquals("BLOCKED", result.state());
            assertFalse(result.usableForE8() || result.canIssueVcu());
        }
        var otherFuel = annual(List.of(first, withCanonical(second, new OpenIrIntraYearReuse.Canonical(
            CANONICAL.fleetSnapshot(), CANONICAL.ratingSnapshot(), UUID.randomUUID(), CANONICAL.cycle()))));
        assertTrue(has(otherFuel, "FUEL_REFERENCE_MISMATCH"));
        assertFalse(has(otherFuel, "FLEET_REFERENCE_MISMATCH"));
        assertNull(otherFuel.totals());
        assertNotEquals(same.manifestSha256(), otherFuel.manifestSha256());
    }

    @Test
    void aCalculatedWindowWithoutCanonicalReferencesBlocksTheAnnualTotal() {
        var missing = annual(List.of(withCanonical(member("M1", START, END, "1", "0", "0", "1"), null)));
        assertTrue(has(missing, "FLEET_FUEL_REFERENCE_MISSING"));
        assertNull(missing.totals());
        var incomplete = annual(List.of(withCanonical(member("M1", START, END, "1", "0", "0", "1"),
            new OpenIrIntraYearReuse.Canonical(UUID.randomUUID(), null, UUID.randomUUID(), "COMBINED"))));
        assertTrue(has(incomplete, "FLEET_FUEL_REFERENCE_MISSING"));
        // A window that is not calculated is not compared: only the missing run is reported.
        var notCalculated = annual(List.of(new OpenE8AnnualMath.Member("M1", null, 0, null, START, END, null,
            "MISSING", false, null, null)));
        assertFalse(has(notCalculated, "FLEET_FUEL_REFERENCE_MISSING"));
    }

    private static OpenE8AnnualMath.Member withIr(OpenE8AnnualMath.Member m, OpenE8AnnualMath.IrReference ir) {
        return new OpenE8AnnualMath.Member(m.period(), m.runId(), m.version(), m.inputSha256(), m.windowStart(),
            m.windowEndExclusive(), m.projectYear(), m.state(), m.stale(), m.totals(), ir, m.canonical());
    }

    private static OpenE8AnnualMath.Member withCanonical(OpenE8AnnualMath.Member m, OpenIrIntraYearReuse.Canonical canonical) {
        return new OpenE8AnnualMath.Member(m.period(), m.runId(), m.version(), m.inputSha256(), m.windowStart(),
            m.windowEndExclusive(), m.projectYear(), m.state(), m.stale(), m.totals(), m.ir(), canonical);
    }

    private static OpenE8AnnualMath.Result annual(List<OpenE8AnnualMath.Member> members) {
        return OpenE8AnnualMath.consolidate("project", 1, START, END, null, members);
    }

    private static OpenE8AnnualMath.Member member(String period, Instant start, Instant end,
                                                 String be, String pe, String le, String er) {
        return new OpenE8AnnualMath.Member(period, UUID.randomUUID(), 1, "hash-" + period,
            start, end, 1, "REVIEWED_INTERNAL", false,
            new OpenE8AnnualMath.Totals(be, pe, le, er, "0.00"), IR, CANONICAL);
    }

    private static boolean has(OpenE8AnnualMath.Result result, String code) {
        return result.issues().stream().anyMatch(i -> code.equals(i.code()));
    }
}
