package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenPeriodQaOperationalEventsTest {
    private static final OffsetDateTime FROM=OffsetDateTime.parse("2027-02-01T00:00:00Z");
    private static final OffsetDateTime UNTIL=OffsetDateTime.parse("2027-02-01T01:00:00Z");
    private static final Instant CAL_START=Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant CAL_END=Instant.parse("2028-01-01T00:00:00Z");

    private static OpenPeriodQa.Meter meter(String code,String kind) {
        return new OpenPeriodQa.Meter(code,1,"DCFC-1",1,1,kind,
            "DCFC","SIN","GRID_ONLY",true,CAL_START,CAL_END,CAL_START,CAL_END);
    }
    private static OpenPeriodQa.Reading reading(String code,String kind,String amount) {
        return new OpenPeriodQa.Reading(UUID.randomUUID(),2,code,1,kind,
            FROM.toInstant(),UNTIL.toInstant(),new BigDecimal(amount),"CANDIDATE",
            "CANDIDATE","ACCEPTED_FOR_QA",1,"source-sha","line-sha");
    }
    private static final UUID INCIDENT_ID=UUID.fromString("4fa955bd-0a57-45cd-9cad-34d804b631cc");
    private static OpenPeriodQa.Incident incident(String outcome,int version,Instant from,Instant end) {
        return new OpenPeriodQa.Incident(INCIDENT_ID,"SOURCE_OUTAGE","SOURCE","METER-API-1",
            from,end,version,outcome);
    }
    private static OpenPeriodQa.Result qa(List<OpenPeriodQa.Incident> incidents) {
        return OpenPeriodQa.evaluate(FROM,UNTIL,List.of("DCFC-1"),
            List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(reading("ED-1","ED","2700"),reading("EC-1","EC","3000")),incidents);
    }

    @Test
    void pendingIncidentBlocksPreviouslyReconciledEnergyWithoutReplacingMissingWithZero() {
        assertTrue(qa(List.of()).readyForInternalSnapshot());
        var report=qa(List.of(incident(null,0,FROM.toInstant(),UNTIL.toInstant())));
        assertFalse(report.readyForInternalSnapshot());
        assertFalse(report.canRunE8());
        assertNull(report.edKwh());
        assertNull(report.ecKwh());
        assertTrue(report.gates().stream().anyMatch(g->
            g.code().equals("OPERATIONAL_SOURCE_OUTAGE")));
    }

    @Test
    void confirmedBlockPersistsAndIndependentClearanceOnlyLiftsIncidentGate() {
        var report=qa(List.of(incident("CONFIRMED_BLOCKING",1,FROM.toInstant(),UNTIL.toInstant())));
        assertFalse(report.readyForInternalSnapshot());
        var cleared=qa(List.of(incident("CLEARED_FOR_INTERNAL_QA",2,FROM.toInstant(),UNTIL.toInstant())));
        assertTrue(cleared.readyForInternalSnapshot());
        assertEquals("2700",cleared.edKwh());
        assertEquals("3000",cleared.ecKwh());
        assertFalse(cleared.canRunE8());
    }

    @Test
    void touchingButNotOverlappingWindowDoesNotBlock() {
        var before=incident(null,0,FROM.minusHours(1).toInstant(),FROM.toInstant());
        var after=incident(null,0,UNTIL.toInstant(),UNTIL.plusHours(1).toInstant());
        assertTrue(qa(List.of(before,after)).readyForInternalSnapshot());
    }

    @Test
    void incidentAndReviewAreAlwaysPartOfTheFrozenHash() {
        var refs=qa(List.of()).accepted();
        String old=OpenMeasurementReviewQa.hash("project",FROM,UNTIL,refs);
        assertEquals(old,OpenMeasurementReviewQa.hash("project",FROM,UNTIL,refs,List.of()));
        String pending=OpenMeasurementReviewQa.hash("project",FROM,UNTIL,refs,
            List.of(incident(null,0,FROM.toInstant(),UNTIL.toInstant())));
        String cleared=OpenMeasurementReviewQa.hash("project",FROM,UNTIL,refs,
            List.of(incident("CLEARED_FOR_INTERNAL_QA",1,FROM.toInstant(),UNTIL.toInstant())));
        assertNotEquals(old,pending);
        assertNotEquals(old,cleared);
        assertNotEquals(pending,cleared);
        assertEquals(cleared,OpenMeasurementReviewQa.hash("project",FROM,UNTIL,refs,
            List.of(incident("CLEARED_FOR_INTERNAL_QA",1,FROM.toInstant(),UNTIL.toInstant()))));
    }

    @Test
    void secondMonitoringPeriodIsNotContaminatedByPriorOutage() {
        var incident=incident(null,0,FROM.toInstant(),UNTIL.toInstant());
        var marchFrom=OffsetDateTime.parse("2027-03-01T00:00:00Z");
        var marchUntil=marchFrom.plusHours(1);
        var report=OpenPeriodQa.evaluate(marchFrom,marchUntil,List.of("DCFC-1"),
            List.of(meter("ED-1","ED"),meter("EC-1","EC")),List.of(),List.of(incident));
        assertFalse(report.gates().stream().anyMatch(g->g.code().startsWith("OPERATIONAL_")));
        assertTrue(report.gates().stream().anyMatch(g->g.code().equals("HOUR_GAP_ED")));
    }
}
