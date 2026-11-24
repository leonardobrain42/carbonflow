package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenDySearchCoverageTest {
    private static final OffsetDateTime FROM=OffsetDateTime.parse("2034-07-01T03:00:00Z");
    private static final OffsetDateTime UNTIL=OffsetDateTime.parse("2035-07-01T03:00:00Z");
    private static OpenDySearchCoverage.Row row(String kind,String territory,String result) {
        return new OpenDySearchCoverage.Row("Programme",kind,territory,result,
            LocalDate.parse("2034-07-01"),LocalDate.parse("2035-07-01"),LocalDate.parse("2035-07-02"),
            "Registry","1","test locator","Declared universe");
    }

    @Test
    void negativeResultsRequireBothCategoriesAndFullCoverageButDoNotProveAbsence() {
        var zero=OpenDyAssessmentRules.values("NO_FLEET_CREDITS_DECLARED","0","0",null);
        var erc=row("ERC","NATIONAL_BR","NO_RESULTS");
        var erf=row("ERF","NATIONAL_BR","NO_RESULTS");
        var rows=OpenDySearchCoverage.normalize(List.of(erf,erc));
        assertEquals("ERC",rows.get(0).creditKind());
        assertDoesNotThrow(()->OpenDySearchCoverage.validate(rows,List.of(),zero,FROM,UNTIL));
        assertThrows(IllegalArgumentException.class,()->OpenDySearchCoverage.validate(List.of(erc),List.of(),zero,FROM,UNTIL));
        assertThrows(IllegalArgumentException.class,()->OpenDySearchCoverage.normalize(List.of(erc,erc)));
        assertThrows(IllegalArgumentException.class,()->OpenDySearchCoverage.validate(
            List.of(erc,row("ERF","PARTIAL_BR","NO_RESULTS")),List.of(),zero,FROM,UNTIL));
        assertThrows(IllegalArgumentException.class,()->OpenDySearchCoverage.validate(
            List.of(erc,row("ERF","NATIONAL_BR","FOUND")),List.of(),zero,FROM,UNTIL));
        var unknown=OpenDyAssessmentRules.values("INCONCLUSIVE",null,null,null);
        assertDoesNotThrow(()->OpenDySearchCoverage.validate(
            List.of(row("ERF","PARTIAL_BR","INCOMPLETE")),List.of(),unknown,FROM,UNTIL));
        assertNull(unknown.preview());
    }
}
