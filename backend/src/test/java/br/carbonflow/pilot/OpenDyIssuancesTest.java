package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

class OpenDyIssuancesTest {
    private static OpenDyIssuances.Input row(String id,String kind,String amount,String day) {
        return new OpenDyIssuances.Input("P","Project",id,kind,LocalDate.parse(day),"BR",amount,"S","1","test locator");
    }
    @Test
    void reconcilesExactlyAndRejectsDuplicatedIssuanceAndBoundary() {
        var from=OffsetDateTime.parse("2034-07-01T03:00:00Z");
        var until=OffsetDateTime.parse("2035-07-01T03:00:00Z");
        var values=OpenDyAssessmentRules.values("FLEET_CREDITS_FOUND","75","25",null);
        var a=row("A","ERC","75","2034-07-01");
        var b=row("B","ERF","25","2035-06-30");
        var rows=OpenDyIssuances.normalize(List.of(b,a));
        assertEquals("A",rows.get(0).issuanceId());
        assertDoesNotThrow(()->OpenDyIssuances.reconcile(rows,values,from,until));
        assertThrows(IllegalArgumentException.class,()->OpenDyIssuances.normalize(List.of(a,row("a","ERF","25","2034-07-01"))));
        assertThrows(IllegalArgumentException.class,()->OpenDyIssuances.reconcile(
            OpenDyIssuances.normalize(List.of(a,row("B","ERF","25","2035-07-01"))),values,from,until));
        assertThrows(IllegalArgumentException.class,()->OpenDyIssuances.reconcile(
            OpenDyIssuances.normalize(List.of(a)),values,from,until));
        assertThrows(IllegalArgumentException.class,()->OpenDyIssuances.normalize(List.of(row("Z","ERC","0","2034-07-01"))));
    }
}
