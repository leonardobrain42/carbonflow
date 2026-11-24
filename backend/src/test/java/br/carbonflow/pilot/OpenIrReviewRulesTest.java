package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenIrReviewRulesTest {
    @Test
    void firstYearReviewChecksPlanNotObservedUpdate() {
        assertDoesNotThrow(()->OpenIrReviewRules.validate("INITIAL_BASELINE_CHECKED","PLAN_ONLY","PENDING_REVIEW",1));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("INITIAL_BASELINE_CHECKED","OBSERVED_UPDATE","PENDING_REVIEW",1));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("INITIAL_BASELINE_CHECKED","PLAN_ONLY","PENDING_METHOD",1));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("INITIAL_BASELINE_CHECKED","PLAN_ONLY","PENDING_REVIEW",2));
        assertThrows(IllegalArgumentException.class,()->OpenIrReviewRules.validate("CHECKED_INTERNAL","OBSERVED_UPDATE","PENDING_REVIEW",1));
        String plan="Fonte e responsável pela atualização anual.";
        assertEquals(plan,new OpenIrAssessments.InitialPlan(" "+plan+" ",plan,plan,plan).validated().evr());
        assertThrows(IllegalArgumentException.class,()->new OpenIrAssessments.InitialPlan(plan,plan,"",plan).validated());
        assertThrows(IllegalArgumentException.class,()->new OpenIrAssessments.InitialPlan(null,plan,plan,plan).validated());
    }

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
