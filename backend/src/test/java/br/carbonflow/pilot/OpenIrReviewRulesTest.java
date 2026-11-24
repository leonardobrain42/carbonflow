package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenIrReviewRulesTest {
    @Test
    void onlyObservedUpdateWithAnnualSpecificCandidateMayBeChecked() {
        assertDoesNotThrow(()->OpenIrReviewRules.validate("CHECKED_INTERNAL","OBSERVED_UPDATE","PENDING_REVIEW"));
        for(String basis:new String[]{"PLAN_ONLY","INSUFFICIENT"})
            assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("CHECKED_INTERNAL",basis,"PENDING_REVIEW"));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("CHECKED_INTERNAL","OBSERVED_UPDATE","PENDING_METHOD"));
        assertDoesNotThrow(()->OpenIrReviewRules.validate("NEEDS_INFORMATION","PLAN_ONLY","PENDING_REVIEW"));
        assertDoesNotThrow(()->OpenIrReviewRules.validate("REJECTED","INSUFFICIENT","PENDING_METHOD"));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate(null,"PLAN_ONLY","PENDING_REVIEW"));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("REJECTED",null,"PENDING_REVIEW"));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("APPROVED","OBSERVED_UPDATE","PENDING_REVIEW"));
    }
}
