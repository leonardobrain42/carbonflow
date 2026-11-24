package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenIrIntraYearReuseTest {
    private static LocalDate d(String s) { return LocalDate.parse(s); }
    private final UUID fleet=UUID.randomUUID(),rating=UUID.randomUUID(),fuel=UUID.randomUUID();
    private final OpenIrAnnualTemporalEvidence.Window year=new OpenIrAnnualTemporalEvidence.Window(
        d("2028-01-15"),d("2029-01-15"),d("2028-02-01"),d("2028-03-01"),d("2028-07-01"));

    private OpenIrIntraYearReuse.Canonical refs() {
        return new OpenIrIntraYearReuse.Canonical(fleet,rating,fuel,"COMBINED");
    }
    private OpenIrIntraYearReuse.Subject assessed(int projectYear,int sourceYear) {
        return new OpenIrIntraYearReuse.Subject(projectYear,sourceYear,refs(),
            new OpenIrIntraYearReuse.Span(d("2028-02-01"),d("2028-03-01")));
    }
    private OpenIrIntraYearReuse.Subject target(int projectYear,int sourceYear,OpenIrIntraYearReuse.Canonical c,
                                                String from,String until) {
        return new OpenIrIntraYearReuse.Subject(projectYear,sourceYear,c,
            new OpenIrIntraYearReuse.Span(d(from),d(until)));
    }
    private List<OpenIrAnnualTemporalEvidence.Item> evidence(String from,String until) {
        return Arrays.stream(OpenIrAnnualTemporalEvidence.Metric.values()).map(m->
            new OpenIrAnnualTemporalEvidence.Item(new OpenIrAnnualTemporalEvidence.Input(
                m,d("2028-02-15"),d(from),d(until),"DOC-"+m),"a".repeat(64))).toList();
    }

    @Test void samePairEquivalentReferencesAndCoveredPeriodReuse() {
        assertEquals(OpenIrIntraYearReuse.Outcome.REUSABLE,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-06-01","2028-07-01"),year,evidence("2028-01-15","2029-01-15")));
    }
    @Test void differentRelativeYearOrSourceYearNeverReuses() {
        var ok=evidence("2028-01-15","2029-01-15");
        assertEquals(OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(3,2028,refs(),"2029-02-01","2029-03-01"),year,ok));
        assertEquals(OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2029,refs(),"2028-06-01","2028-07-01"),year,ok));
    }
    @Test void anyDivergentOrMissingCanonicalReferenceBlocks() {
        var ok=evidence("2028-01-15","2029-01-15");
        for(var other:List.of(
            new OpenIrIntraYearReuse.Canonical(UUID.randomUUID(),rating,fuel,"COMBINED"),
            new OpenIrIntraYearReuse.Canonical(fleet,UUID.randomUUID(),fuel,"COMBINED"),
            new OpenIrIntraYearReuse.Canonical(fleet,rating,UUID.randomUUID(),"COMBINED"),
            new OpenIrIntraYearReuse.Canonical(fleet,rating,fuel,"OTHER"),
            new OpenIrIntraYearReuse.Canonical(fleet,rating,null,"COMBINED"))) {
            assertEquals(OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH,OpenIrIntraYearReuse.decide(assessed(2,2028),
                target(2,2028,other,"2028-06-01","2028-07-01"),year,ok));
        }
        assertEquals(OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,null,"2028-06-01","2028-07-01"),year,ok));
    }
    @Test void firstYearIsNeverReusedHere() {
        assertEquals(OpenIrIntraYearReuse.Outcome.REFERENCE_MISMATCH,OpenIrIntraYearReuse.decide(assessed(1,2027),
            target(1,2027,refs(),"2027-06-01","2027-07-01"),year,evidence("2027-01-15","2028-01-15")));
    }
    @Test void periodOutsideFrozenAnnualWindowRequiresRecheck() {
        var ok=evidence("2027-01-01","2030-01-01");
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-01-01","2028-02-01"),year,ok));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-12-01","2029-02-01"),year,ok));
        // exclusive end exactly at the year boundary is still inside the year
        assertEquals(OpenIrIntraYearReuse.Outcome.REUSABLE,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-12-15","2029-01-15"),year,ok));
    }
    @Test void declaredValidityMustCoverEveryDayOfThePeriodForEveryMetric() {
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-06-01","2028-07-01"),year,evidence("2028-06-02","2029-01-15")));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-06-01","2028-07-01"),year,evidence("2028-01-15","2028-06-30")));
        var one=new ArrayList<>(evidence("2028-01-15","2029-01-15"));
        var first=one.get(0).evidence();
        one.set(0,new OpenIrAnnualTemporalEvidence.Item(new OpenIrAnnualTemporalEvidence.Input(first.metric(),
            first.updatedOn(),d("2028-07-01"),first.validUntil(),first.evidenceLocator()),"a".repeat(64)));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,OpenIrIntraYearReuse.decide(assessed(2,2028),
            target(2,2028,refs(),"2028-06-01","2028-07-01"),year,one));
    }
    @Test void incompleteDuplicateOrMissingEvidenceFailsClosed() {
        var target=target(2,2028,refs(),"2028-06-01","2028-07-01");
        var all=evidence("2028-01-15","2029-01-15");
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,
            OpenIrIntraYearReuse.decide(assessed(2,2028),target,year,all.subList(0,3)));
        var duplicate=new ArrayList<>(all);duplicate.set(1,all.get(0));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,
            OpenIrIntraYearReuse.decide(assessed(2,2028),target,year,duplicate));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,
            OpenIrIntraYearReuse.decide(assessed(2,2028),target,year,null));
        assertEquals(OpenIrIntraYearReuse.Outcome.EVIDENCE_NOT_COVERING,
            OpenIrIntraYearReuse.decide(assessed(2,2028),target,null,all));
    }
    @Test void markerSeparatesReuseFromExactContextAndBindsPeriodAndAssessment() {
        var span=new OpenIrIntraYearReuse.Span(d("2028-06-01"),d("2028-07-01"));
        var id=UUID.randomUUID();
        String base=OpenIrIntraYearReuse.marker("r".repeat(64),"M1",span,id,"a".repeat(64));
        assertEquals(base,OpenIrIntraYearReuse.marker("r".repeat(64),"M1",span,id,"a".repeat(64)));
        assertNotEquals("r".repeat(64),base);
        assertNotEquals(base,OpenIrIntraYearReuse.marker("r".repeat(64),"M1",
            new OpenIrIntraYearReuse.Span(d("2028-06-01"),d("2028-07-02")),id,"a".repeat(64)));
        assertNotEquals(base,OpenIrIntraYearReuse.marker("r".repeat(64),"M2",span,id,"a".repeat(64)));
        assertNotEquals(base,OpenIrIntraYearReuse.marker("r".repeat(64),"M1",span,id,"b".repeat(64)));
    }
}
