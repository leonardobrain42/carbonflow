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
}
