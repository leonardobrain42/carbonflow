package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.OffsetDateTime;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class OpenMeasurementCsvTest {
    private static final String SOURCE="METER-API-1";
    private static final String HEADER=String.join(",",OpenMeasurementCsv.HEADER)+"\n";

    private static OpenMeasurementCsv.MeterScope meter(String code,String kind,
        String topology,OffsetDateTime calUntil) {
        return new OpenMeasurementCsv.MeterScope(code,1,kind,"DCFC-1",1,1,
            "DCFC","SIN",topology,"America/Sao_Paulo",SOURCE,
            OffsetDateTime.parse("2027-01-01T00:00:00Z"),calUntil,
            OffsetDateTime.parse("2027-01-01T00:00:00Z"),
            OffsetDateTime.parse("2028-01-01T00:00:00Z"));
    }

    private static Map<String,OpenMeasurementCsv.MeterScope> registered() {
        return Map.of("ED-1",meter("ED-1","ED","GRID_ONLY",
                          OffsetDateTime.parse("2028-01-01T00:00:00Z")),
            "EC-1",meter("EC-1","EC","GRID_ONLY",
                          OffsetDateTime.parse("2028-01-01T00:00:00Z")));
    }

    private static OpenMeasurementCsv.Preview preview(String rows) {
        return OpenMeasurementCsv.parse((HEADER+rows).getBytes(StandardCharsets.UTF_8),
            registered(),Set.of(),SOURCE);
    }

    @Test
    void distinctEdAndEcRemainUnapprovedEvenWithEvidenceBackedConfigurations() {
        var report=preview(
            "ED-1,ED-001,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,America/Sao_Paulo,2700,kWh\n"+
            "EC-1,EC-001,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,America/Sao_Paulo,3000,kWh\n");
        assertFalse(report.quarantined());
        assertEquals(2,report.rows().size());
        assertEquals("CANDIDATE",report.rows().get(0).state());
        assertEquals(new BigDecimal("2700"),report.rows().get(0).kwh());
        assertEquals(new BigDecimal("3000"),report.rows().get(1).kwh());
        assertNotEquals(report.rows().get(0).kwh(),report.rows().get(1).kwh());
    }

    @Test
    void detectsDuplicatedPhysicalIntervalWithinAndAcrossUploads() {
        String single="ED-1,ED-001,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,10,kWh\n";
        var preview=preview(single+single);
        assertTrue(preview.quarantined());
        assertTrue(preview.rows().get(1).reasons().contains("DUPLICATE_SOURCE_INTERVAL"));
        var first=preview(single).rows().getFirst();
        var second=OpenMeasurementCsv.parse((HEADER+single).getBytes(StandardCharsets.UTF_8),
            registered(),Set.of(OpenMeasurementCsv.fingerprint(first)),SOURCE);
        assertTrue(second.rows().getFirst().reasons().contains("DUPLICATE_SOURCE_INTERVAL"));
    }

    @Test
    void quarantineMissingZeroNegativeSourceMismatchAndUncalibratedIntervals() {
        var p=preview(
            "ED-1,MISSING,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,,kWh\n"+
            "ED-1,ZERO,ED,2027-02-01T01:00:00Z,2027-02-01T02:00:00Z,UTC,0,kWh\n"+
            "ED-1,NEGATIVE,ED,2027-02-01T02:00:00Z,2027-02-01T03:00:00Z,UTC,-5,kWh\n");
        assertTrue(p.quarantined());
        assertTrue(p.rows().get(0).reasons().contains("ENERGY_MISSING"));
        assertTrue(p.rows().get(1).reasons().contains("ZERO_REQUIRES_EVIDENCE"));
        assertTrue(p.rows().get(2).reasons().contains("NEGATIVE_ENERGY"));
        assertNull(p.rows().get(2).kwh());

        var bad=OpenMeasurementCsv.parse((HEADER+
            "ED-1,R2,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,1,kWh\n")
                .getBytes(StandardCharsets.UTF_8),
            Map.of("ED-1",meter("ED-1","ED","GRID_ONLY",
                 OffsetDateTime.parse("2027-01-15T00:00:00Z"))),
            Set.of(),"OTHER-SOURCE");
        assertTrue(bad.rows().getFirst().reasons().contains("SOURCE_SYSTEM_MISMATCH"));
        assertTrue(bad.rows().getFirst().reasons().contains("CALIBRATION_NOT_COVERING"));
    }

    @Test
    void rejectsNonHourlyZoneMismatchWrongMeterAndUnsupportedTopology() {
        var report=OpenMeasurementCsv.parse((HEADER+
            "ED-1,BAD,EC,2027-02-01T00:00:00+02:00,2027-02-01T02:00:00+02:00,America/Sao_Paulo,2,MWh\n")
            .getBytes(StandardCharsets.UTF_8),
            Map.of("ED-1",meter("ED-1","ED","V2G",
                 OffsetDateTime.parse("2028-01-01T00:00:00Z"))),
            Set.of(),SOURCE);
        var reasons=report.rows().getFirst().reasons();
        assertTrue(reasons.contains("NON_HOURLY_INTERVAL"));
        assertTrue(reasons.contains("TIME_ZONE_INVALID"));
        assertTrue(reasons.contains("METER_KIND_MISMATCH"));
        assertTrue(reasons.contains("UNSUPPORTED_TOPOLOGY"));
    }

    @Test
    void invalidEncodingHeaderAndEmptyFileAreRejectedWithoutPublishing() {
        assertThrows(PilotProblem.class,()->OpenMeasurementCsv.parse(
            new byte[]{(byte)0xc3,(byte)0x28},registered(),Set.of(),SOURCE));
        assertThrows(PilotProblem.class,()->OpenMeasurementCsv.parse(
            "meter,amount\nED-1,2\n".getBytes(StandardCharsets.UTF_8),registered(),Set.of(),SOURCE));
        assertThrows(PilotProblem.class,()->OpenMeasurementCsv.parse(
            new byte[0],registered(),Set.of(),SOURCE));
    }

    @Test
    void preservesOriginalUnitsAndAvoidsPrecisionLoss() {
        var rows=preview("ED-1,A,ED,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,2500,Wh\n"+
            "EC-1,B,EC,2027-02-01T00:00:00Z,2027-02-01T01:00:00Z,UTC,0.003,MWh\n");
        assertFalse(rows.quarantined());
        assertEquals(new BigDecimal("2.500"),rows.rows().get(0).kwh());
        assertEquals(new BigDecimal("3"),rows.rows().get(1).kwh());
    }
}
