package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/** VM0038 v1.1 Eq.4 for the existing grid-only SIN branch. */
final class OpenHourlyProjectEmissions {
    record Hour(Instant start, BigDecimal edKwh, BigDecimal ecKwh, BigDecimal factor) {}
    record Series(Instant from, Instant until, String region, List<Hour> hours) {}
    record Term(String hourStart, String edKwh, String ecKwh, String factorTco2ePerKwh,
                String peTco2e) {}
    record Memory(String windowStart, String windowEndExclusive, String region,
                  String edKwh, String ecKwh, String peTco2e, List<Term> hours) {}

    private OpenHourlyProjectEmissions() {}

    static Memory calculate(Series series) {
        if (series == null || !"SIN".equals(series.region()))
            throw invalid("A série exige a região SIN.");
        Instant from = series.from(), until = series.until();
        if (from == null || until == null || !from.isBefore(until)
                || !from.equals(from.truncatedTo(ChronoUnit.HOURS))
                || !until.equals(until.truncatedTo(ChronoUnit.HOURS)))
            throw invalid("A janela exige início e fim exclusivo em horas UTC inteiras.");
        long expected = Duration.between(from, until).toHours();
        if (expected > 744 || series.hours() == null || series.hours().size() != expected
                || series.hours().stream().anyMatch(java.util.Objects::isNull))
            throw invalid("A série exige todas as horas da janela, até 744 horas.");
        var sorted = new ArrayList<>(series.hours());
        if (sorted.stream().anyMatch(hour -> hour.start() == null))
            throw invalid("Hora ausente na série.");
        sorted.sort(Comparator.comparing(Hour::start));
        BigDecimal ed = BigDecimal.ZERO, ec = BigDecimal.ZERO, pe = BigDecimal.ZERO;
        var terms = new ArrayList<Term>();
        Instant next = from;
        for (Hour hour : sorted) {
            if (!next.equals(hour.start()))
                throw invalid("A série contém lacuna, duplicação ou hora fora da janela.");
            BigDecimal delivered = value(hour.edKwh(), 12);
            BigDecimal consumed = value(hour.ecKwh(), 12);
            BigDecimal factor = value(hour.factor(), 18);
            if (consumed.compareTo(delivered) < 0)
                throw invalid("EC é menor que ED no ramo de rede simples.");
            // Exact decimal products and sum. No per-hour rounding.
            BigDecimal emissions = consumed.multiply(factor);
            ed = ed.add(delivered);
            ec = ec.add(consumed);
            pe = pe.add(emissions);
            terms.add(new Term(next.toString(), number(delivered), number(consumed),
                number(factor), number(emissions)));
            next = next.plus(1, ChronoUnit.HOURS);
        }
        return new Memory(from.toString(), until.toString(), series.region(),
            number(ed), number(ec), number(pe), List.copyOf(terms));
    }

    private static BigDecimal value(BigDecimal input, int scale) {
        if (input == null) throw invalid("Energia ou fator ausente.");
        var normalized = input.stripTrailingZeros();
        if (normalized.signum() < 0 || normalized.precision() > 30
                || normalized.scale() > scale || normalized.precision() - normalized.scale() > 18)
            throw invalid("Energia ou fator com valor ou precisão inválidos.");
        return normalized;
    }

    private static String number(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static IllegalArgumentException invalid(String reason) {
        return new IllegalArgumentException(reason);
    }
}
