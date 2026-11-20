package br.carbonflow.pilot;

import static br.carbonflow.pilot.OpenMonitoringModel.*;
import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenMonitoringChecksTest {
    private static final OffsetDateTime FROM=OffsetDateTime.parse("2027-02-01T00:00:00Z");
    private static final OffsetDateTime UNTIL=OffsetDateTime.parse("2027-03-01T00:00:00Z");

    private static Charger charger(int version, String topology) {
        return new Charger("C-1",version,new ChargerInput("SITE-1","DCFC",
            new BigDecimal("150"),"SIN",topology,"America/Sao_Paulo","EVID-CHARGER-1"),
            UUID.randomUUID(),FROM);
    }
    private static Meter meter(String code,String kind,int chargerVersion,
                               OffsetDateTime calibrationEnd) {
        return new Meter(code,1,chargerVersion,new MeterInput("C-1",kind,
            "ED".equals(kind)?"DOWNSTREAM":"UPSTREAM","meter-api",
            "CALIBRATION-1",OffsetDateTime.parse("2027-01-01T00:00:00Z"),
            calibrationEnd,OffsetDateTime.parse("2027-01-01T00:00:00Z"),
            OffsetDateTime.parse("2028-01-01T00:00:00Z"),"America/Sao_Paulo"),
            UUID.randomUUID(),FROM);
    }

    @Test
    void plannedMeteringDoesNotInventEdEcOrEmissions() {
        var result=OpenMonitoringChecks.evaluate(
            List.of(charger(1,"GRID_ONLY")),
            List.of(meter("ED-1","ED",1,OffsetDateTime.parse("2028-01-01T00:00:00Z")),
                    meter("EC-1","EC",1,OffsetDateTime.parse("2028-01-01T00:00:00Z"))),
            FROM,UNTIL);
        assertEquals("PENDING_MEASUREMENT",result.state());
        assertFalse(result.canUseForE8());
        assertNull(result.edKwh());
        assertNull(result.ecKwh());
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("ED_EC_OBSERVATIONS") &&
            g.state().equals("PENDING_MEASUREMENT")));
        assertFalse(result.toString().contains("2700"));
        assertFalse(result.toString().contains("3000"));
    }

    @Test
    void configurationDiagnosesMissingEdAndEcIndependently() {
        var none=OpenMonitoringChecks.evaluate(List.of(charger(1,"GRID_ONLY")),List.of(),null,null);
        assertTrue(none.gates().stream().anyMatch(g -> g.code().equals("ED_METER_MISSING")));
        assertTrue(none.gates().stream().anyMatch(g -> g.code().equals("EC_METER_MISSING")));
        var onlyEd=OpenMonitoringChecks.evaluate(List.of(charger(1,"GRID_ONLY")),
            List.of(meter("ED-1","ED",1,OffsetDateTime.parse("2028-01-01T00:00:00Z"))),null,null);
        assertFalse(onlyEd.gates().stream().anyMatch(g -> g.code().equals("ED_METER_MISSING")));
        assertTrue(onlyEd.gates().stream().anyMatch(g -> g.code().equals("EC_METER_MISSING")));
    }

    @Test
    void calibrationExpiredAndChargerChangeBlockCoverage() {
        var result=OpenMonitoringChecks.evaluate(List.of(charger(2,"GRID_ONLY")),
            List.of(meter("ED-1","ED",1,OffsetDateTime.parse("2027-02-15T00:00:00Z")),
                    meter("EC-1","EC",2,OffsetDateTime.parse("2028-01-01T00:00:00Z"))),
            FROM,UNTIL);
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("CALIBRATION_NOT_COVERING") && g.meterCode().equals("ED-1")));
        assertTrue(result.gates().stream().anyMatch(g ->
            g.code().equals("CHARGER_CONFIGURATION_STALE") && g.meterCode().equals("ED-1")));
        assertFalse(result.canUseForE8());
    }

    @Test
    void topologyAndDuplicateMeterRoleRequireSeparateResolution() {
        var result=OpenMonitoringChecks.evaluate(List.of(charger(1,"V2G")),
            List.of(meter("ED-1","ED",1,OffsetDateTime.parse("2028-01-01T00:00:00Z")),
                    meter("ED-2","ED",1,OffsetDateTime.parse("2028-01-01T00:00:00Z"))),null,null);
        assertEquals("BLOCKED_OUT_OF_SCOPE",result.state());
        assertTrue(result.gates().stream().anyMatch(g -> g.code().equals("CHARGER_TOPOLOGY")));
        assertTrue(result.gates().stream().anyMatch(g -> g.code().equals("METER_ROLE_CONFLICT")));
    }

    @Test
    void rejectsMissingHalfOfPeriodAndZeroLength() {
        assertThrows(PilotProblem.class,() -> OpenMonitoringChecks.evaluate(
            List.of(),List.of(),FROM,null));
        assertThrows(PilotProblem.class,() -> OpenMonitoringChecks.evaluate(
            List.of(),List.of(),FROM,FROM));
    }

    @Test
    void normalizationRejectsBadUnitsPositionsAndInconsistentOffsets() {
        assertThrows(PilotProblem.class, () ->
            OpenMonitoringConfiguration.normalize(new ChargerInput("SIM-DCFC-01","DCFC",
                new BigDecimal("50"),"SIN","GRID_ONLY","UTC","SOURCE-1")));
        assertThrows(PilotProblem.class, () ->
            OpenMonitoringConfiguration.normalize(new ChargerInput("DCFC-01","DCFC",
                new BigDecimal("-1"),"SIN","GRID_ONLY","UTC","SOURCE-1")));
        assertThrows(PilotProblem.class, () ->
            OpenMonitoringConfiguration.normalize(new MeterInput("DCFC-01","ED","UPSTREAM",
                null,null,null,null,null,null,"UTC")));
        assertThrows(PilotProblem.class, () ->
            OpenMonitoringConfiguration.normalize(new MeterInput("DCFC-01","ED","DOWNSTREAM",
                "remote-api","CAL-01",OffsetDateTime.parse("2027-01-01T00:00:00+02:00"),
                OffsetDateTime.parse("2027-02-01T00:00:00+02:00"),null,null,"America/Sao_Paulo")));
    }
}
