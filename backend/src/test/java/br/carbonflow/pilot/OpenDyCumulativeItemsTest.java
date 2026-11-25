package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenDyCumulativeItemsTest {
    private static final LocalDate START=LocalDate.parse("2033-07-01");
    private static final OffsetDateTime ANNUAL_FROM=OffsetDateTime.parse("2034-07-01T03:00:00Z");
    private static OpenDyIssuances.Input in(String id,String kind,String amount,String day) {
        return new OpenDyIssuances.Input("P","Project",id,kind,LocalDate.parse(day),"BR",amount,"S","1","test locator");
    }
    private static final OpenDyAssessmentRules.Values ANNUAL=OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","75","25",null);
    private static final OpenDyAssessmentRules.Cumulative CUM=OpenDyAssessmentRules.cumulative("100","40",2,ANNUAL);

    @Test
    void itemsPlusAnnualTotalsMustEqualDeclaredCumulative() {
        var items=OpenDyCumulativeItems.normalize(List.of(in("C","ERF","15","2034-06-30"),in("B","ERC","25","2033-07-01")),CUM);
        assertEquals("B",items.get(0).issuanceId());
        assertDoesNotThrow(()->OpenDyCumulativeItems.reconcile(items,List.of(),ANNUAL,CUM,START,ANNUAL_FROM));
        var short_=OpenDyCumulativeItems.normalize(List.of(in("B","ERC","24","2033-08-01"),in("C","ERF","15","2033-08-01")),CUM);
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.reconcile(short_,List.of(),ANNUAL,CUM,START,ANNUAL_FROM));
    }

    @Test
    void rejectsItemsOutsideTheEarlierSpanAndRepeatsOfAnnualItems() {
        var annualRows=OpenDyIssuances.normalize(List.of(in("A","ERC","75","2034-08-01"),in("F","ERF","25","2034-09-01")));
        var late=OpenDyCumulativeItems.normalize(List.of(in("B","ERC","25","2034-07-01"),in("C","ERF","15","2034-01-01")),CUM);
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.reconcile(late,annualRows,ANNUAL,CUM,START,ANNUAL_FROM),
            "the annual window start is excluded from the earlier span");
        var early=OpenDyCumulativeItems.normalize(List.of(in("B","ERC","25","2033-06-30"),in("C","ERF","15","2034-01-01")),CUM);
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.reconcile(early,annualRows,ANNUAL,CUM,START,ANNUAL_FROM),
            "before the start of crediting");
        var repeat=OpenDyCumulativeItems.normalize(List.of(in("a","ERC","25","2033-08-01"),in("C","ERF","15","2034-01-01")),CUM);
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.reconcile(repeat,annualRows,ANNUAL,CUM,START,ANNUAL_FROM),
            "an annual issuance cannot be counted again");
    }

    @Test
    void itemsNeedDeclaredCumulativeTotalsAndEmptyIsLegacy() {
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.normalize(List.of(in("B","ERC","25","2033-08-01")),null));
        assertTrue(OpenDyCumulativeItems.normalize(null,null).isEmpty());
        assertDoesNotThrow(()->OpenDyCumulativeItems.reconcile(List.of(),List.of(),ANNUAL,CUM,START,ANNUAL_FROM));
        assertThrows(IllegalArgumentException.class,()->OpenDyCumulativeItems.normalize(List.of(in("B","ERC","0","2033-08-01")),CUM));
    }
}
