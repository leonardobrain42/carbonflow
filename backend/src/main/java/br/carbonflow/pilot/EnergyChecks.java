package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;

import static br.carbonflow.pilot.EnergyImportModel.*;

final class EnergyChecks {
    record Preview(String classification, List<Reason> reasons, Fact fact) {}

    static Preview preview(String raw, int columns, IntakeModel.Snapshot state, String period) {
        List<String> fields;
        try {
            fields = EnergyCsv.fields(raw);
        } catch (IllegalArgumentException error) {
            return issue("INVALIDA", "CSV_MALFORMADO", error.getMessage(), null);
        }
        if (fields.size() != columns) {
            return issue("INVALIDA", "COLUNAS_DIVERGENTES", "A linha não tem o mesmo número de colunas do cabeçalho.", null);
        }
        String kind = fields.get(0);
        String factId = nullable(fields.get(1));
        String site = fields.get(5);
        String charger = nullable(fields.get(6));
        String vehicle = nullable(fields.get(7));
        String circuit = nullable(fields.get(8));
        String meter = fields.get(9);
        String quantity = nullable(fields.get(10));
        String unit = fields.get(11);
        String evidence = columns == 13 ? nullable(fields.get(12)) : null;
        if ((!kind.equals("EC") && !kind.equals("ED")) || (factId != null && !IntakeRules.id(factId))
                || !IntakeRules.id(site) || !IntakeRules.id(meter)
                || (kind.equals("ED") && (!IntakeRules.id(charger) || !IntakeRules.id(vehicle) || circuit != null))
                || (kind.equals("EC") && (!IntakeRules.id(circuit) || vehicle != null || (charger != null && !IntakeRules.id(charger))))
                || (evidence != null && !IntakeRules.id(evidence))) {
            return issue("INVALIDA", "IDENTIFICACAO_INVALIDA", "Confira classe e os identificadores sintéticos do contorno.", null);
        }
        if (!List.of("Wh", "kWh", "MWh").contains(unit)) {
            return issue("INVALIDA", "UNIDADE_INVALIDA", "Informe energia em Wh, kWh ou MWh.", null);
        }
        OffsetDateTime start;
        OffsetDateTime end;
        try {
            start = OffsetDateTime.parse(fields.get(2));
            end = OffsetDateTime.parse(fields.get(3));
        } catch (DateTimeParseException error) {
            return issue("INVALIDA", "FUSO_OU_DATA_INVALIDA", "Início e fim exigem deslocamento de fuso explícito.", null);
        }
        if (!start.isBefore(end) || start.getNano() % 1000 != 0 || end.getNano() % 1000 != 0) {
            return issue("INVALIDA", "INTERVALO_INVALIDO", "O fim deve suceder o início e os instantes devem ter precisão de até microssegundos.", null);
        }
        String zone = fields.get(4);
        if (!VintageWindows.ZONE.getId().equals(zone) || !validOffset(start) || !validOffset(end)) {
            return issue("INVALIDA", "FUSO_OU_DATA_INVALIDA", "Use America/Sao_Paulo e instantes em UTC ou com seu deslocamento válido.", null);
        }
        String kwh = null;
        if (quantity != null) {
            if (!IntakeRules.decimal(quantity)) {
                return issue("INVALIDA", "DECIMAL_INVALIDO", "Informe energia decimal não negativa, com ponto e sem expoente.", null);
            }
            var value = new BigDecimal(quantity);
            if ("Wh".equals(unit)) value = value.movePointLeft(3);
            if ("MWh".equals(unit)) value = value.movePointRight(3);
            kwh = value.stripTrailingZeros().toPlainString();
        }

        var fact = new Fact(kind, factId, site, charger, vehicle, circuit, meter, fields.get(2), fields.get(3), zone,
                start, end, quantity, unit, kwh, evidence);
        if (quantity == null) {
            return issue("AUSENTE", "ENERGIA_AUSENTE", "A energia está ausente e permanece sem valor.", fact);
        }
        var startOfPeriod = startOfPeriod(state, period);
        if (startOfPeriod == null) {
            return issue("PENDENTE", "OPERACAO_PENDENTE", "Registre a operação na ficha antes de admitir ED/EC.", fact);
        }
        var window = new VintageWindows.Window(startOfPeriod, startOfPeriod.plusYears(1));
        if (!window.contains(start) || end.isAfter(window.ending())
                || VintageWindows.split(window).stream().noneMatch(cut -> cut.contains(start) && !end.isAfter(cut.ending()))) {
            return issue("PENDENTE", "LEITURA_SEM_SEGMENTACAO",
                    "O intervalo deve estar inteiro no período e em um só recorte de vintage. Preserve o original.", fact);
        }

        var inventory = state.intake().candidate().inventory();
        var problems = new ArrayList<Reason>();
        if (kind.equals("ED")) {
            var station = inventory.stations().stream()
                    .filter(item -> item.id().equals(charger) && item.site().equals(site)).findFirst().orElse(null);
            boolean inFleet = inventory.vehicles().stream().anyMatch(item -> item.id().equals(vehicle));
            if (station == null || !inFleet) {
                return issue("FORA_DO_ESCOPO", "ED_FORA_DA_FROTA", "O veículo ou a estação está fora do inventário da ficha.", fact);
            }
            // Certificados simulados do ED para as duas estações da massa. A instalação usa a prova de comissionamento.
            String suffix = charger.equals("SIM-DCFC-01") ? "01" : charger.equals("SIM-DCFC-02") ? "02" : "";
            var coverage = new VintageWindows.Window(state.operation().start(), state.operation().start().plusYears(2));
            if (suffix.isEmpty() || !meter.equals("SIM-ED-" + suffix) || !coverage.contains(start) || end.isAfter(coverage.ending())) {
                problems.add(new Reason("METROLOGIA_ED_PENDENTE", "O certificado sintético de ED não cobre o instrumento e o intervalo."));
            }
        } else {
            var config = inventory.ecCircuits().stream().filter(item -> item.id().equals(circuit)).findFirst().orElse(null);
            if (config == null || (charger != null && !charger.equals(config.station()))
                    || inventory.stations().stream().noneMatch(item -> item.id().equals(config.station()) && item.site().equals(site))) {
                return issue("FORA_DO_ESCOPO", "EC_FORA_DO_CONTORNO", "O circuito e o pátio não correspondem à configuração.", fact);
            }
            var meterView = IntakeRules.covering(state, circuit, start, end);
            if (meterView == null || !meterView.revision().details().meterId().equals(meter)) {
                problems.add(new Reason("REVISAO_SEM_COBERTURA", "O medidor ou sua revisão não cobre todo o intervalo."));
            } else {
                if (!IntakeRules.segregated(meterView.revision())) {
                    problems.add(new Reason("EC_NAO_SEGREGADA", "Falta prova do contorno exclusivo a montante."));
                }
                if (!IntakeRules.calibrated(meterView.revision(), start, end)) {
                    problems.add(new Reason("CALIBRACAO_SEM_COBERTURA", "A calibração não cobre todo o intervalo."));
                }
            }
        }
        if (kwh.equals("0") && !zeroProven(fact)) {
            problems.add(new Reason("PENDENTE_ZERO", "Zero medido exige prova aplicável ao contorno e ao intervalo."));
        }
        return new Preview(problems.isEmpty() ? "CANDIDATA" : "PENDENTE", List.copyOf(problems), fact);
    }

    private static boolean validOffset(OffsetDateTime instant) {
        return instant.getOffset().equals(java.time.ZoneOffset.UTC)
                || instant.getOffset().equals(VintageWindows.ZONE.getRules().getOffset(instant.toInstant()));
    }

    private static boolean zeroProven(Fact fact) {
        return "SIM-PROVA-ZERO-EC-01".equals(fact.evidence()) && "EC".equals(fact.kind())
                && "SIM-EC-01".equals(fact.circuit()) && "SIM-MED-01".equals(fact.meter())
                && fact.start().isEqual(OffsetDateTime.parse("2028-03-31T18:00:00-03:00"))
                && fact.endExclusive().isEqual(OffsetDateTime.parse("2028-03-31T19:00:00-03:00"));
    }

    static LocalDate startOfPeriod(IntakeModel.Snapshot state, String period) {
        return state.operation() == null ? null
                : state.operation().creditStart().plusYears("M1".equals(period) ? 0 : 1);
    }

    static String hash(Fact fact) {
        String canonical = String.join("|", fact.kind(), fact.channel(), fact.vehicle() == null ? "" : fact.vehicle(),
                fact.meter(), fact.start().toInstant().toString(), fact.endExclusive().toInstant().toString(), fact.kwh());
        return FixtureCatalog.sha256(canonical.getBytes(StandardCharsets.UTF_8));
    }

    private static Preview issue(String classification, String code, String message, Fact fact) {
        return new Preview(classification, List.of(new Reason(code, message)), fact);
    }

    private static String nullable(String value) { return value.isEmpty() ? null : value; }
}
