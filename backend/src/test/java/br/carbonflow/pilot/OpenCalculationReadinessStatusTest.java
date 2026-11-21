package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

class OpenCalculationReadinessStatusTest {
    @Test
    void storedStatusesBecomePlainSituations() {
        String[] codes={
            "BLOCKED_STALE","BLOCKED_DOCUMENTARY","PENDING_INTERNAL_REVIEW",
            "RETURNED_FOR_CORRECTION","CHECKED_INTERNAL_AWAITING_NORMATIVE_ADMISSION",
            "CANDIDATE_MISSING","STALE_NOT_ADMITTED","NOT_ADMITTED",
            "EFEL_SOURCE_MISSING","SUPERSEDED_NOT_ADMITTED",
            "PENDING_INTERNAL_REVIEW_NOT_ADMITTED","RETURNED_NOT_ADMITTED",
            "CHECKED_INTERNAL_NOT_ADMITTED","VT0011_SOURCE_MISSING",
            "VT0011_WORKBOOK_MISSING","SIMPLE_OM_A1_MISSING",
            "LCMR_SCREEN_PASSED_PRELIMINARY_NOT_ADMITTED",
            "LCMR_SCREEN_FAILED_PRELIMINARY_NOT_ADMITTED"
        };
        for (String code : codes) {
            String text=OpenCalculationReadiness.plainStatus(code);
            assertFalse(text.isBlank(), code);
            assertFalse(text.contains(code), text);
            assertFalse(text.contains("_"), text);
        }
        String unknown=OpenCalculationReadiness.plainStatus("NEW_INTERNAL_CODE");
        assertFalse(unknown.contains("NEW_INTERNAL_CODE"));
        assertFalse(unknown.contains("_"));
        assertTrue(OpenCalculationReadiness.plainStatus(null).contains("sem texto público"));
    }
}
