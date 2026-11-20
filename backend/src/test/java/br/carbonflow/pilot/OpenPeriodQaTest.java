package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.math.BigDecimal;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OpenPeriodQaTest {
    private static final OffsetDateTime FROM=OffsetDateTime.parse("2027-02-01T00:00:00Z");
    private static final OffsetDateTime TO=OffsetDateTime.parse("2027-02-01T01:00:00Z");
    private static final Instant CAL_FROM=Instant.parse("2027-01-01T00:00:00Z");
    private static final Instant CAL_TO=Instant.parse("2028-01-01T00:00:00Z");

    private static OpenPeriodQa.Meter meter(String code,String kind) {
        return new OpenPeriodQa.Meter(code,1,"CH-1",1,1,kind,
            "DCFC","SIN","GRID_ONLY",true,CAL_FROM,CAL_TO,CAL_FROM,CAL_TO);
    }

    private static OpenPeriodQa.Reading reading(String code,String kind,String amount) {
        return new OpenPeriodQa.Reading(UUID.randomUUID(),2,code,1,kind,
            FROM.toInstant(),TO.toInstant(),new BigDecimal(amount),
            "CANDIDATE","CANDIDATE","ACCEPTED_FOR_QA",1,"sha-original","sha-line");
    }

    private static OpenPeriodQa.Result qa(List<OpenPeriodQa.Meter> meters,
                                            List<OpenPeriodQa.Reading> readings) {
        return OpenPeriodQa.evaluate(FROM,TO,List.of("CH-1"),meters,readings);
    }

    @Test
    void distinctEdEcWithIndependentQaCreatesReconciledPreviewButNeverE8() {
        var result=qa(List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(reading("ED-1","ED","2700"),reading("EC-1","EC","3000")));
        assertTrue(result.readyForInternalSnapshot());
        assertEquals("READY_FOR_INTERNAL_QA_SNAPSHOT",result.state());
        assertFalse(result.canRunE8());
        assertEquals("2700",result.edKwh());
        assertEquals("3000",result.ecKwh());
        assertEquals(2,result.accepted().size());
        assertEquals(1,result.accepted().getFirst().reviewVersion());
    }

    @Test
    void missingEcCannotTurnIntoZeroOrProducePartialAggregate() {
        var result=qa(List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(reading("ED-1","ED","2700")));
        assertFalse(result.readyForInternalSnapshot());
        assertNull(result.edKwh());
        assertNull(result.ecKwh());
        assertTrue(result.gates().stream().anyMatch(g -> g.code().equals("HOUR_GAP_EC")));
    }

    @Test
    void ecBelowEdFlagsAnomalyAndBlocksClosing() {
        var result=qa(List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(reading("ED-1","ED","3000"),reading("EC-1","EC","2700")));
        assertFalse(result.readyForInternalSnapshot());
        assertTrue(result.gates().stream().anyMatch(g->g.code().equals("EC_BELOW_ED")));
    }

    @Test
    void expiredCalibrationOrOldChargerVersionDoesNotProduceSnapshot() {
        var ed=meter("ED-1","ED");
        var expired=new OpenPeriodQa.Meter(ed.code(),ed.version(),ed.charger(),
            ed.chargerVersion(),ed.currentChargerVersion(),ed.kind(),ed.chargerKind(),
            ed.grid(),ed.topology(),true,CAL_FROM,
            Instant.parse("2027-01-31T23:00:00Z"),CAL_FROM,CAL_TO);
        var stale=new OpenPeriodQa.Meter("EC-1",1,"CH-1",1,2,"EC",
            "DCFC","SIN","GRID_ONLY",true,CAL_FROM,CAL_TO,CAL_FROM,CAL_TO);
        var result=qa(List.of(expired,stale),
            List.of(reading("ED-1","ED","2700"),reading("EC-1","EC","3000")));
        assertFalse(result.readyForInternalSnapshot());
        assertNull(result.edKwh());
        assertTrue(result.gates().stream().anyMatch(g->g.code().equals("METER_CALIBRATION_STALE")));
        assertTrue(result.gates().stream().anyMatch(g->g.code().equals("CHARGER_VERSION_STALE")));
    }

    @Test
    void solarBatteryAndV2gMustRemainOutOfScope() {
        var ed=meter("ED-1","ED");
        var v2g=new OpenPeriodQa.Meter(ed.code(),ed.version(),ed.charger(),
            1,1,"ED","DCFC","SIN","V2G",true,CAL_FROM,CAL_TO,CAL_FROM,CAL_TO);
        var result=qa(List.of(v2g,meter("EC-1","EC")),
            List.of(reading("ED-1","ED","2700"),reading("EC-1","EC","3000")));
        assertFalse(result.readyForInternalSnapshot());
        assertTrue(result.gates().stream().anyMatch(g->g.code().equals("OUT_OF_SCOPE_TOPOLOGY")));
    }

    @Test
    void duplicateHourAndUnreviewedDataNeverDoubleCount() {
        var a=reading("ED-1","ED","2700");
        var b=reading("ED-1","ED","2800");
        var result=qa(List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(a,b,reading("EC-1","EC","3000")));
        assertFalse(result.readyForInternalSnapshot());
        assertTrue(result.gates().stream().anyMatch(g->g.code().equals("HOUR_OVERLAP_ED")));
        var unreviewed=new OpenPeriodQa.Reading(a.batch(),2,a.meterCode(),1,"ED",
            a.start(),a.end(),a.kwh(),"CANDIDATE","CANDIDATE",null,0,"hash","hash");
        var pending=qa(List.of(meter("ED-1","ED"),meter("EC-1","EC")),
            List.of(unreviewed,reading("EC-1","EC","3000")));
        assertNull(pending.edKwh());
        assertTrue(pending.gates().stream().anyMatch(g->g.code().equals("REVIEW_MISSING")));
    }

    @Test
    void missingChargerMetersAndInvalidPeriodCannotBeConsideredReady() {
        assertFalse(OpenPeriodQa.evaluate(FROM,TO,List.of("CH-1"),List.of(),List.of())
            .readyForInternalSnapshot());
        assertThrows(PilotProblem.class,()->OpenPeriodQa.evaluate(FROM,FROM,
            List.of(),List.of(),List.of()));
        assertThrows(PilotProblem.class,()->OpenPeriodQa.evaluate(FROM,
            OffsetDateTime.parse("2027-02-01T00:30:00Z"),
            List.of(),List.of(),List.of()));
    }
}
