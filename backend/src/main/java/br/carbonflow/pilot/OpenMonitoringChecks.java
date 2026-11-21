package br.carbonflow.pilot;

import static br.carbonflow.pilot.OpenMonitoringModel.*;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

/**
 * Configuration QA only. Meter configurations and calibration declarations
 * are never equivalent to raw ED/EC observations or an approved E8 snapshot.
 */
final class OpenMonitoringChecks {
    private OpenMonitoringChecks() {}

    static Readiness evaluate(List<Charger> chargers, List<Meter> meters,
                              OffsetDateTime periodStart, OffsetDateTime periodEndExclusive) {
        var gates = new ArrayList<Gate>();
        boolean outside = false;
        if (chargers.isEmpty()) {
            add(gates, "CHARGER_MISSING", "MISSING",
                "Nenhum carregador foi cadastrado para o projeto.", null, null);
        }
        if ((periodStart == null) != (periodEndExclusive == null) ||
                (periodStart != null && !periodStart.toInstant().isBefore(periodEndExclusive.toInstant()))) {
            throw PilotProblem.invalid("PERIODO_INVALIDO", "period", "CF-OPEN-009",
                "Informe ambos os instantes em ordem crescente, ou deixe ambos vazios.");
        }
        for (Charger charger : chargers) {
            String code = charger.code();
            ChargerInput info = charger.details();
            if (!"DCFC".equals(info.chargerKind()) || !"SIN".equals(info.grid()) ||
                    !"GRID_ONLY".equals(info.topology())) {
                outside = true;
                add(gates, "CHARGER_TOPOLOGY", "OUT_OF_SCOPE",
                    "Este ramo suporta DCFC somente SIN, sem geração, armazenamento ou V2G.", code, null);
            }
            if (info.ratedPowerKw() == null) {
                add(gates, "CHARGER_POWER", "MISSING",
                    "Informe a potência nominal com fonte verificável.", code, null);
            }
            if (info.sourceReference() == null) {
                add(gates, "CHARGER_PROVENANCE", "MISSING",
                    "Inclua referência de evidência do inventário e comissionamento.", code, null);
            }
            if ("UNKNOWN".equals(info.timeZone())) {
                add(gates, "CHARGER_TIME_ZONE", "MISSING",
                    "O fuso da instalação precisa ser confirmado.", code, null);
            }

            var belonging = meters.stream().filter(m -> code.equals(m.details().chargerCode())).toList();
            var ed = belonging.stream().filter(m -> "ED".equals(m.details().energyKind())).toList();
            var ec = belonging.stream().filter(m -> "EC".equals(m.details().energyKind())).toList();
            if (ed.isEmpty()) {
                add(gates, "ED_METER_MISSING", "MISSING",
                    "Defina o medidor a jusante da energia entregue aos veículos.", code, null);
            }
            if (ec.isEmpty()) {
                add(gates, "EC_METER_MISSING", "MISSING",
                    "Defina o medidor a montante da energia consumida pela infraestrutura, distinto da energia entregue.", code, null);
            }
            if (ed.size() > 1 || ec.size() > 1) {
                add(gates, "METER_ROLE_CONFLICT", "NEEDS_REVIEW",
                    "Múltiplos medidores da mesma variável exigem um plano de agregação e fronteira.",
                    code, null);
            }
            if (ed.size() == 1 && ec.size() == 1 && ed.getFirst().code().equals(ec.getFirst().code())) {
                add(gates, "METER_BOUNDARY_CONFLICT", "NEEDS_REVIEW",
                    "Um medidor sem fronteiras distintas não comprova a energia entregue e a energia consumida.", code, null);
            }
            for (Meter meter : belonging) {
                MeterInput measurement = meter.details();
                if (meter.chargerVersion() != charger.version()) {
                    add(gates, "CHARGER_CONFIGURATION_STALE", "STALE",
                        "O medidor foi configurado contra revisão antiga do carregador.", code, meter.code());
                }
                if (measurement.sourceSystem() == null) {
                    add(gates, "METER_SOURCE_MISSING", "MISSING",
                        "Informe a origem das leituras e a identificação do sistema de medição.",
                        code, meter.code());
                }
                if (Set.of("UNKNOWN").contains(measurement.timeZone())) {
                    add(gates, "METER_TIME_ZONE", "MISSING",
                        "O fuso do instrumento está pendente.", code, meter.code());
                }
                if (measurement.calibrationEvidenceRef() == null ||
                        measurement.calibrationValidFrom() == null ||
                        measurement.calibrationValidUntil() == null) {
                    add(gates, "CALIBRATION_MISSING", "MISSING",
                        "Não há referência e período completos de calibração do instrumento.",
                        code, meter.code());
                } else if (periodStart == null) {
                    add(gates, "CALIBRATION_PERIOD_UNSET", "NEEDS_REVIEW",
                        "Selecionar período de monitoramento antes de avaliar cobertura temporal.",
                        code, meter.code());
                } else {
                    if (!covers(measurement.calibrationValidFrom(),
                            measurement.calibrationValidUntil(), periodStart, periodEndExclusive)) {
                        add(gates, "CALIBRATION_NOT_COVERING", "BLOCKED",
                            "A calibração declarada não cobre todo o intervalo solicitado.",
                            code, meter.code());
                    }
                    if (measurement.configuredFrom() == null ||
                        !covers(measurement.configuredFrom(), measurement.configuredUntil(),
                            periodStart, periodEndExclusive)) {
                        add(gates, "METER_CONFIGURATION_NOT_COVERING", "BLOCKED",
                            "A configuração operacional não cobre todo o intervalo solicitado.",
                            code, meter.code());
                    }
                }
                add(gates, "CALIBRATION_NOT_REVIEWED", "PENDING_REVIEW",
                    "Declaração de calibração não equivale a certificado admitido por revisor independente.",
                    code, meter.code());
            }
        }

        // A scoped, immutable raw measurement pipeline for dynamic projects is
        // not yet implemented. Never reuse SIM observations or count bookings.
        add(gates, "ED_EC_OBSERVATIONS", "PENDING_MEASUREMENT",
            "Nenhum lote de medição de energia entregue e consumida foi admitido. Dados de cobrança ou sessões não são energia medida.",
            null, null);
        return new Readiness(outside ? "BLOCKED_OUT_OF_SCOPE" : "PENDING_MEASUREMENT",
            true, false, null, null, List.copyOf(gates));
    }

    private static boolean covers(OffsetDateTime start, OffsetDateTime end,
                                  OffsetDateTime targetStart, OffsetDateTime targetEnd) {
        if (start == null || end == null || targetStart == null || targetEnd == null) return false;
        Instant a = start.toInstant(), b = end.toInstant();
        return !a.isAfter(targetStart.toInstant()) && !b.isBefore(targetEnd.toInstant());
    }

    private static void add(List<Gate> gates, String code, String state,
                            String reason, String chargerCode, String meterCode) {
        gates.add(new Gate(code, state, reason, chargerCode, meterCode));
    }
}
