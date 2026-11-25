package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenE8DyContextTest {
    private static String classify(Integer year,boolean exists,boolean stale,boolean conclusive,boolean arithmetic,
                                   boolean absence,boolean itemized,boolean reviewed,boolean shared) {
        return OpenE8DyContext.classify(year,exists,stale,conclusive,arithmetic,absence,itemized,reviewed,shared);
    }

    @Test
    void missingTimingAndStaleNeverReachAChecked() {
        assertEquals("TIMING_UNRESOLVED",classify(null,true,false,true,true,true,false,true,false));
        assertEquals("MISSING_FOR_PROJECT_YEAR",classify(2,false,false,false,false,false,false,false,false));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",classify(1,false,false,false,false,false,false,false,false));
        assertEquals("STALE",classify(2,true,true,true,true,true,false,true,false));
    }

    @Test
    void needsConclusiveArithmeticallyConsistentAndExactlyReviewed() {
        assertEquals(OpenE8DyContext.CHECKED,classify(2,true,false,true,true,true,false,true,false));
        assertEquals("REVIEW_REQUIRED",classify(2,true,false,false,true,true,false,true,false),"inconclusive");
        assertEquals("REVIEW_REQUIRED",classify(2,true,false,true,false,true,false,true,false),"arithmetic mismatch");
        assertEquals("REVIEW_REQUIRED",classify(2,true,false,true,true,true,false,false,false),"no review of the exact version");
    }

    @Test
    void positiveFindingNeedsItemizedIssuancesAndRateioStaysBlocked() {
        assertEquals("REVIEW_REQUIRED",classify(3,true,false,true,true,false,false,true,false));
        assertEquals(OpenE8DyContext.CHECKED,classify(3,true,false,true,true,false,true,true,false));
        assertEquals(OpenE8DyContext.ALLOCATION_BLOCKED,classify(3,true,false,true,true,false,true,true,true),
            "share by EVs in circulation waits for the national EVR denominator (G03)");
    }

    @Test
    void yearOneOnlyWithProvedAbsence() {
        assertEquals(OpenE8DyContext.FIRST_YEAR_ABSENCE,classify(1,true,false,true,true,true,false,true,false));
        assertEquals("FIRST_YEAR_REVIEW_REQUIRED",classify(1,true,false,true,true,false,true,true,false),
            "a positive finding in year 1 needs a specific decision");
    }

    @Test
    void discountIsOnlyAvailableWhenCheckedAndIsExactlyTheDeclaredCredits() {
        var id=UUID.randomUUID();
        var checked=OpenE8DyContext.of("p",3,id,1,"a".repeat(64),OpenE8DyContext.CHECKED,"b".repeat(64),
            UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("75"),new BigDecimal("25"),false);
        assertTrue(checked.usable());
        assertEquals(0,new BigDecimal("0.75").compareTo(OpenGridCalculationMath.factor(checked.discount())));
        var absent=OpenE8DyContext.of("p",3,id,1,"a".repeat(64),OpenE8DyContext.CHECKED,"b".repeat(64),
            UUID.randomUUID(),UUID.randomUUID(),new BigDecimal("10"),BigDecimal.ZERO,true);
        assertEquals(0,BigDecimal.ONE.compareTo(OpenGridCalculationMath.factor(absent.discount())));
        var review=OpenE8DyContext.of("p",3,id,1,"a".repeat(64),"REVIEW_REQUIRED",null,null,null,null,null,false);
        assertFalse(review.usable());
        assertNull(review.discount());
        assertNotEquals(checked.contextSha256(),absent.contextSha256());
        assertEquals(checked.contextSha256(),OpenE8DyContext.of("p",3,id,1,"a".repeat(64),OpenE8DyContext.CHECKED,
            "b".repeat(64),checked.author(),checked.reviewer(),new BigDecimal("75"),new BigDecimal("25"),false).contextSha256());
        assertTrue(checked.authoredBy(checked.reviewer()));
        assertFalse(checked.authoredBy(UUID.randomUUID()));
    }
}
