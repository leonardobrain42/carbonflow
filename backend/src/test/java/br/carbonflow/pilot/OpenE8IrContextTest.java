package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenE8IrContextTest {
    @Test
    void onlyCheckedCurrentExactAnnualContextCanSupplyRehearsalValue() {
        assertEquals("TIMING_UNRESOLVED",OpenE8IrContext.classify(null,null,false,false,false,false,false,false,false,false));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,true,false,true,true,true,true,false));
        assertEquals("MISSING_FOR_PROJECT_YEAR",OpenE8IrContext.classify(2,2027,true,false,false,false,false,false,false,false));
        assertEquals("STALE",OpenE8IrContext.classify(2,2027,true,true,true,true,true,true,true,false));
        assertEquals("PERIOD_REFERENCE_MISMATCH",OpenE8IrContext.classify(2,2027,true,true,false,false,true,true,true,false));
        assertEquals("REVIEW_REQUIRED",OpenE8IrContext.classify(2,2027,true,true,false,true,false,true,true,false));
        assertEquals("REVIEW_REQUIRED",OpenE8IrContext.classify(2,2027,true,true,false,true,true,true,false,false));
        assertEquals("ANNUAL_EVIDENCE_RECHECK_REQUIRED",OpenE8IrContext.classify(2,2027,true,true,false,true,true,false,true,false));
        assertEquals(OpenE8IrContext.CHECKED,OpenE8IrContext.classify(2,2027,true,true,false,true,true,true,true,false));
    }

    @Test
    void firstYearExAntePassesOnlyWithLinkedPlanCurrentExactAnnualRouteAndNeverAsMonitored() {
        // timing, exists, stale, samePeriodRefs, checked, annualProof, candidateOne, planLinked
        assertEquals(OpenE8IrContext.FIRST_YEAR_EX_ANTE,
            OpenE8IrContext.classify(1,2026,true,true,false,true,false,false,true,true));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,true,false,true,false,false,true,false));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,false,false,false,false,false,true,true));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,true,true,true,false,false,true,true));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,true,false,false,false,false,true,true));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",OpenE8IrContext.classify(1,2026,true,true,false,true,false,false,false,true));
        assertEquals("TIMING_UNRESOLVED",OpenE8IrContext.classify(1,null,true,true,false,true,false,false,true,true));
        // the first-year path never opens the later-year states
        assertEquals("REVIEW_REQUIRED",OpenE8IrContext.classify(2,2027,true,true,false,true,false,true,true,true));
        assertNotEquals(OpenE8IrContext.CHECKED,OpenE8IrContext.FIRST_YEAR_EX_ANTE);
    }

    @Test
    void firstYearExAnteSuppliesTheRehearsalOneOnlyWithAnAssessment() {
        UUID id=UUID.randomUUID(),author=UUID.randomUUID(),reviewer=UUID.randomUUID();
        var ex=OpenE8IrContext.of("P","M1",1,2026,id,1,"a".repeat(64),OpenE8IrContext.FIRST_YEAR_EX_ANTE,
            "b".repeat(64),null,author,reviewer);
        assertEquals(BigDecimal.ONE,ex.rehearsalValue());
        assertEquals("FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED",ex.reference().state());
        assertNull(OpenE8IrContext.of("P","M1",1,2026,null,null,null,OpenE8IrContext.FIRST_YEAR_EX_ANTE,
            null,null,null,null).rehearsalValue());
        assertNull(OpenE8IrContext.of("P","M1",1,2026,id,1,"a".repeat(64),"FIRST_YEAR_REVIEW_REQUIRED",
            "b".repeat(64),null,author,reviewer).rehearsalValue());
        var checked=OpenE8IrContext.of("P","M1",1,2026,id,1,"a".repeat(64),OpenE8IrContext.CHECKED,
            "b".repeat(64),null,author,reviewer);
        assertNotEquals(ex.contextSha256(),checked.contextSha256());
    }

    @Test
    void stateAndReviewProofChangeContextEvenWhenAssessmentIdDoesNot() {
        UUID id=UUID.randomUUID(),author=UUID.randomUUID(),reviewer=UUID.randomUUID();
        String hash="a".repeat(64);
        var before=OpenE8IrContext.of("P","M2",2,2027,id,1,hash,"REVIEW_REQUIRED",null,null,author,null);
        var after=OpenE8IrContext.of("P","M2",2,2027,id,1,hash,OpenE8IrContext.CHECKED,
            "b".repeat(64),"c".repeat(64),author,reviewer);
        assertNull(before.rehearsalValue());
        assertEquals(BigDecimal.ONE,after.rehearsalValue());
        assertNotEquals(before.contextSha256(),after.contextSha256());
        assertTrue(after.authoredBy(author));
        assertTrue(after.authoredBy(reviewer));
        assertFalse(after.authoredBy(UUID.randomUUID()));
        assertEquals("STRUCTURED_PROJECT_SOURCE_YEAR",after.reference().basis());
        assertEquals(hash,after.reference().sha256());
        var changed=OpenE8IrContext.of("P","M2",2,2027,id,1,hash,OpenE8IrContext.CHECKED,
            "b".repeat(64),"d".repeat(64),author,reviewer);
        assertNotEquals(after.contextSha256(),changed.contextSha256());
    }
}
