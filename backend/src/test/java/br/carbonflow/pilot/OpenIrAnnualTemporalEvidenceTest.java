package br.carbonflow.pilot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import java.util.ArrayList;
import java.util.Collections;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenIrAnnualTemporalEvidenceTest {
    private static LocalDate d(String s){return LocalDate.parse(s);}
    private final OpenIrAnnualTemporalEvidence.Window window=new OpenIrAnnualTemporalEvidence.Window(
        d("2028-01-15"),d("2029-01-15"),d("2028-02-01"),d("2028-03-01"),d("2028-07-01"));
    private final OpenIrAnnualEvidence.Snapshot snapshot=new OpenIrAnnualEvidence.Snapshot(
        UUID.randomUUID(),"P",2,2028,"a".repeat(64),UUID.randomUUID(),UUID.randomUUID(),UUID.randomUUID(),
        "COMBINED",OffsetDateTime.parse("2028-03-01T00:00:00Z"),true,true);

    private OpenIrAnnualTemporalEvidence.Input input(OpenIrAnnualTemporalEvidence.Metric metric,String date,String from,String until) {
        return new OpenIrAnnualTemporalEvidence.Input(metric,d(date),d(from),d(until),"DOC-"+metric);
    }
    private List<OpenIrAnnualTemporalEvidence.Input> valid() {
        return java.util.Arrays.stream(OpenIrAnnualTemporalEvidence.Metric.values())
            .map(m->input(m,"2028-02-15","2028-01-15","2029-01-15")).toList();
    }
    @Test void completeEvidenceIsOrderIndependentAndBindsMetricAndReference() {
        var p=OpenIrAnnualTemporalEvidence.capture(window,snapshot,valid());
        var reverse=new ArrayList<>(valid());Collections.reverse(reverse);
        assertEquals(p,OpenIrAnnualTemporalEvidence.capture(window,snapshot,reverse));
        assertEquals(OpenIrAnnualTemporalEvidence.commandDigest(valid()),OpenIrAnnualTemporalEvidence.commandDigest(reverse));
        assertEquals(4,p.items().size());
        assertNotEquals(p.items().get(1).referenceSha256(),p.items().get(2).referenceSha256());
        var changed=new ArrayList<>(valid());changed.set(0,input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2028-02-16","2028-01-15","2029-01-15"));
        assertNotEquals(p.digest(),OpenIrAnnualTemporalEvidence.capture(window,snapshot,changed).digest());
    }
    @Test void missingDuplicateFutureOutsideYearAndCoverageGapFailClosed() {
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualTemporalEvidence.capture(window,snapshot,List.of()));
        var duplicate=new ArrayList<>(valid());duplicate.set(1,duplicate.get(0));
        assertThrows(IllegalArgumentException.class,()->OpenIrAnnualTemporalEvidence.capture(window,snapshot,duplicate));
        for(var bad:List.of(
            input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2028-01-14","2028-01-15","2029-01-15"),
            input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2029-01-15","2028-01-15","2029-01-15"),
            input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2028-07-02","2028-01-15","2029-01-15"),
            input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2028-02-15","2028-02-02","2029-01-15"),
            input(OpenIrAnnualTemporalEvidence.Metric.EVR,"2028-02-15","2028-01-15","2028-02-29"))) {
            var items=new ArrayList<>(valid());items.set(0,bad);
            assertThrows(IllegalArgumentException.class,()->OpenIrAnnualTemporalEvidence.capture(window,snapshot,items));
        }
    }
    @Test void localDayWindowIncludesLastInstantNotExclusiveEnd() {
        var calendar=new OpenE8Timing.Calendar(d("2027-01-15"),OpenE8Timing.yearWindow(d("2027-01-15"),2),null);
        var timing=new OpenE8Timing.Context("hash",new OpenE8Timing.Window(
            OffsetDateTime.parse("2028-02-01T03:00:00Z"),OffsetDateTime.parse("2028-02-02T03:00:00Z")),
            new OpenE8Timing.Year(2,2028,null));
        var result=OpenIrAnnualTemporalEvidence.window(calendar,timing,OffsetDateTime.parse("2028-07-01T01:00:00Z"));
        assertEquals(d("2028-02-01"),result.periodFrom());
        assertEquals(d("2028-02-02"),result.periodUntil());
        assertEquals(d("2028-06-30"),result.reviewedOn());
    }
}
