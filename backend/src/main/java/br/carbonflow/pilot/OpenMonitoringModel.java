package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

/** Contracts for OPEN dynamic projects; no pilot IDs or E8 quantities. */
final class OpenMonitoringModel {
    private OpenMonitoringModel() {}

    record ChargerInput(String siteCode, String chargerKind, BigDecimal ratedPowerKw,
                        String grid, String topology, String timeZone, String sourceReference) {}

    record Charger(String code, int version, ChargerInput details, UUID recordedBy,
                   OffsetDateTime recordedAt) {}

    record MeterInput(String chargerCode, String energyKind, String meterPosition,
                      String sourceSystem, String calibrationEvidenceRef,
                      OffsetDateTime calibrationValidFrom, OffsetDateTime calibrationValidUntil,
                      OffsetDateTime configuredFrom, OffsetDateTime configuredUntil,
                      String timeZone) {}

    record Meter(String code, int version, int chargerVersion, MeterInput details,
                 UUID recordedBy, OffsetDateTime recordedAt) {}

    record Gate(String code, String state, String reason, String chargerCode, String meterCode) {}

    record Readiness(String state, boolean canPrepare, boolean canUseForE8,
                     String edKwh, String ecKwh, List<Gate> gates) {}

    record View(String projectId, String environment,
                List<Charger> chargers, List<Meter> meters, Readiness readiness) {}
}
