package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import org.junit.jupiter.api.Test;

class OpenDyAssessmentRulesTest {
    @Test
    void previewsEquationEightWithoutAnImplicitDefault() {
        assertEquals("0.75",OpenDyAssessmentRules.number(OpenDyAssessmentRules.values(
            "FLEET_CREDITS_FOUND","75","25",null).preview()));
        assertEquals("0",OpenDyAssessmentRules.number(OpenDyAssessmentRules.values(
            "FLEET_CREDITS_FOUND","0","25",null).preview()));
        assertEquals("1",OpenDyAssessmentRules.number(OpenDyAssessmentRules.values(
            "NO_FLEET_CREDITS_DECLARED","0","0",null).preview()));
        assertNull(OpenDyAssessmentRules.values("INCONCLUSIVE",null,null,"CREDIT_START_MISSING").preview());
    }

    @Test
    void refusesUnknownOrInconsistentEvidenceAndCalendar() {
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("INCONCLUSIVE","0",null,null));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","10","0",null));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("NO_FLEET_CREDITS_DECLARED","10","1",null));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("NO_FLEET_CREDITS_DECLARED","0","0","FIRST_YEAR_REVIEW_REQUIRED"));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND",null,"1",null));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","-1","1",null));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","0.0000000000001","1",null));
    }

    @Test
    void cumulativeTotalsAreOptionalPairedAndIncludeTheAnnualWindow() {
        var annual=OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","75","25",null);
        assertNull(OpenDyAssessmentRules.cumulative(null,null,2,annual));
        var cumulative=OpenDyAssessmentRules.cumulative("100","40",2,annual);
        assertEquals("100",OpenDyAssessmentRules.number(cumulative.erc()));
        assertEquals("40",OpenDyAssessmentRules.number(cumulative.erf()));
        assertTrue(OpenDyAssessmentRules.number(cumulative.preview()).startsWith("0.714285"));
        assertEquals("0.75",OpenDyAssessmentRules.number(annual.preview()),"the annual reading is untouched");
        assertEquals("75",OpenDyAssessmentRules.number(OpenDyAssessmentRules.cumulative("75","25",2,annual).erc()),"equal to annual is allowed");
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("100",null,2,annual));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative(null,"40",2,annual));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("70","40",2,annual));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("100","20",2,annual));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("100","40",1,annual));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("100","40",2,
            OpenDyAssessmentRules.values("INCONCLUSIVE",null,null,null)));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("-1","40",2,annual));
    }

    @Test
    void zeroCumulativeTotalsOnlyMatchDeclaredAbsence() {
        var absence=OpenDyAssessmentRules.values("NO_FLEET_CREDITS_DECLARED","0","0",null);
        assertEquals("1",OpenDyAssessmentRules.number(OpenDyAssessmentRules.cumulative("0","0",2,absence).preview()));
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.cumulative("0","0",2,
            OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","0","25",null)));
    }
}
