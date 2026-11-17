package br.carbonflow.pilot;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.List;

final class VintageWindows {
    static final ZoneId ZONE = ZoneId.of("America/Sao_Paulo");

    record Window(LocalDate start, LocalDate endExclusive) {
        OffsetDateTime opening() {
            return start.atStartOfDay(ZONE).toOffsetDateTime();
        }

        OffsetDateTime ending() {
            return endExclusive.atStartOfDay(ZONE).toOffsetDateTime();
        }

        boolean contains(OffsetDateTime instant) {
            return !instant.isBefore(opening()) && instant.isBefore(ending());
        }
    }

    static List<Window> split(Window period) {
        List<Window> result = new ArrayList<>();
        for (LocalDate start = period.start(); start.isBefore(period.endExclusive()); ) {
            LocalDate nextYear = LocalDate.of(start.getYear() + 1, 1, 1);
            LocalDate end = nextYear.isBefore(period.endExclusive()) ? nextYear : period.endExclusive();
            result.add(new Window(start, end));
            start = end;
        }
        return List.copyOf(result);
    }

    static Window assign(OffsetDateTime start, OffsetDateTime end, List<Window> windows, String field) {
        if (start == null || end == null || !start.isBefore(end)) {
            throw PilotProblem.invalid("INTERVALO_INVALIDO", field, "REG-E2-19",
                    "Informe início e fim em ordem, com deslocamento de fuso.");
        }
        return windows.stream()
                .filter(window -> window.contains(start) && !end.isAfter(window.ending()))
                .findFirst()
                .orElseThrow(() -> PilotProblem.invalid("LEITURA_SEM_SEGMENTACAO", field, "REG-E2-19",
                        "O agregado deve estar contido em um único recorte de período e vintage."));
    }
}
