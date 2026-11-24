package br.carbonflow.pilot;
import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;
class OpenDyFirstYearTest {
    @Test void exploratoryAnniversaryIsSeparateAndHandlesLeapDay() {
        var context=OpenDyFirstYear.exploratory(LocalDate.of(2032,2,29));
        assertEquals(LocalDate.of(2031,2,28),context.exploratoryFrom());
        assertEquals(LocalDate.of(2032,2,29),context.exploratoryUntilExclusive());
        assertEquals("FIRST_YEAR_EXPLORATORY@1",context.policyVersion());
        assertTrue(context.until().isAfter(context.from()));
        assertNull(OpenDyAssessmentRules.values("INCONCLUSIVE",null,null,"FIRST_YEAR_REVIEW_REQUIRED").preview());
        assertThrows(IllegalArgumentException.class,()->OpenDyAssessmentRules.values(
            "NO_FLEET_CREDITS_DECLARED","0","0","FIRST_YEAR_REVIEW_REQUIRED"));
    }
}
