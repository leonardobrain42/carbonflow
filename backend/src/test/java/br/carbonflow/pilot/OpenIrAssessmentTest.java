package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import static br.carbonflow.pilot.OpenIrAssessment.*;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenIrAssessmentTest {
    private static final UUID AUTHOR = new UUID(1, 1);
    private static final UUID REVIEWER = new UUID(1, 2);

    private static List<Evidence> evidence() {
        return List.of(
            new Evidence(Parameter.EVR, new UUID(2, 1), 1, "a".repeat(64), 2036),
            new Evidence(Parameter.AFEC, new UUID(2, 2), 1, "b".repeat(64), 2036),
            new Evidence(Parameter.MPG, new UUID(2, 3), 1, "c".repeat(64), 2036),
            new Evidence(Parameter.EF_FUEL, new UUID(2, 4), 1, "d".repeat(64), 2036));
    }

    private static Input input(Frequency frequency, Basis basis, List<Evidence> evidence) {
        return new Input("test-project", BRANCH, 2, 2036, frequency, basis, AUTHOR, evidence);
    }

    @Test
    void noDefaultOrUnprovedFrequency() {
        for (var frequency : List.of(Frequency.UNKNOWN, Frequency.NON_ANNUAL)) {
            var result = assess(input(frequency, Basis.PROJECT_SPECIFIC, evidence()), null);
            assertEquals(Status.PENDING_METHOD, result.status());
            assertNull(result.candidate());
        }
        var defaults = assess(input(Frequency.ANNUAL, Basis.DEFAULT_VALUES, evidence()), null);
        assertEquals(Status.PENDING_METHOD, defaults.status());
        assertNull(defaults.candidate());
        assertEquals(Status.OUT_OF_SCOPE, assess(new Input("p", "OTHER", 1, 2036,
            Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, AUTHOR, evidence()), null).status());
    }

    @Test
    void incompleteMismatchedAndDuplicateReferencesBlockCandidate() {
        var missing = assess(input(Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, List.of()), null);
        assertEquals(Status.PENDING_EVIDENCE, missing.status());
        assertTrue(missing.reasons().contains("MISSING_MPG"));
        assertNull(missing.candidate());
        var refs = new ArrayList<>(evidence());
        refs.add(new Evidence(Parameter.EVR, new UUID(4, 1), 1, "a".repeat(64), 2035));
        var duplicate = assess(input(Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, refs), null);
        assertTrue(duplicate.reasons().contains("DUPLICATE_EVR"));
        assertTrue(duplicate.reasons().contains("SOURCE_YEAR_MISMATCH_EVR"));
        assertNull(duplicate.candidate());
    }

    @Test
    void reviewMustBeDistinctAndBoundToExactAssessment() {
        var in = input(Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, evidence());
        var pending = assess(in, null);
        assertEquals(Status.PENDING_REVIEW, pending.status());
        assertEquals(BigDecimal.ONE, pending.candidate());
        String sha = pending.assessmentSha256();
        assertEquals(64, sha.length());
        assertEquals(Status.REVIEW_CONFLICT,
            assess(in, new Review(AUTHOR, sha, true, "fixture")).status());
        assertEquals(Status.REVIEW_REJECTED,
            assess(in, new Review(REVIEWER, sha, false, "fixture")).status());
        assertEquals(Status.PENDING_REVIEW,
            assess(in, new Review(REVIEWER, sha, true, " ")).status());
        var review = new Review(REVIEWER, sha, true, "fixture review");
        var reviewed = assess(in, review);
        assertEquals(Status.REVIEW_RECORDED_NOT_ADMITTED, reviewed.status());
        assertFalse(reviewed.usableForE8());

        var reversed = new ArrayList<>(evidence());
        Collections.reverse(reversed);
        assertEquals(sha, assess(input(Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, reversed), review)
            .assessmentSha256());

        var changed = new ArrayList<>(evidence());
        changed.set(0, new Evidence(Parameter.EVR, new UUID(2, 1), 2, "a".repeat(64), 2036));
        var stale = assess(input(Frequency.ANNUAL, Basis.PROJECT_SPECIFIC, changed), review);
        assertEquals(Status.REVIEW_STALE, stale.status());
        assertNotEquals(sha, stale.assessmentSha256());
        assertFalse(stale.usableForE8());

        var otherProject = new Input("other", BRANCH, 2, 2036, Frequency.ANNUAL,
            Basis.PROJECT_SPECIFIC, AUTHOR, evidence());
        assertEquals(Status.REVIEW_STALE, assess(otherProject, review).status());
        var otherYear = new Input("test-project", BRANCH, 3, 2036, Frequency.ANNUAL,
            Basis.PROJECT_SPECIFIC, AUTHOR, evidence());
        assertEquals(Status.REVIEW_STALE, assess(otherYear, review).status());
    }
}
