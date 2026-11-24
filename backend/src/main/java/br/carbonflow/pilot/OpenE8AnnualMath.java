package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/** Sums frozen period results, never ratings, factors, rounded ER or averaged Dy. */
final class OpenE8AnnualMath {
    static final String ENGINE = "vm0038-open-annual-consolidation@1";

    record Totals(String beTco2e, String peTco2e, String leTco2e,
                  String erTco2e, String erDisplay) {}
    record Member(String period, UUID runId, int version, String inputSha256,
                  Instant windowStart, Instant windowEndExclusive, Integer projectYear,
                  String state, boolean stale, Totals totals) {}
    record Issue(String code, String period, Instant from, Instant until, String detail) {}
    record Result(String projectId, int projectYear, Instant windowStart, Instant windowEndExclusive,
                  String engine, String state, boolean usableForE8, boolean canIssueVcu,
                  Totals totals, List<Member> members, List<Issue> issues, String manifestSha256) {}

    private OpenE8AnnualMath() {}

    static Result consolidate(String project, int year, Instant from, Instant until,
                              String calendarGate, List<Member> candidates) {
        if (project == null || project.isBlank() || year < 1 || year > 100 || candidates == null)
            throw new IllegalArgumentException("Contexto anual inválido.");
        var issues = new ArrayList<Issue>();
        var members = new ArrayList<Member>();
        boolean calendarValid = calendarGate == null && from != null && until != null && from.isBefore(until);
        if (!calendarValid) issues.add(issue(calendarGate == null ? "YEAR_CALENDAR_MISSING" : calendarGate,
            null, null, null, "Confirme o início de crédito e os eventos para determinar o ano do projeto."));

        var ordered = candidates.stream().sorted(Comparator.comparing(Member::period)
            .thenComparing(m -> m.runId() == null ? "" : m.runId().toString())).toList();
        var periods = new HashSet<String>();
        var ids = new HashSet<UUID>();
        for (var member : ordered) {
            Instant start = member.windowStart(), end = member.windowEndExclusive();
            if (start == null || end == null || !start.isBefore(end)) {
                members.add(member);
                issues.add(issue("UNLOCATED_PERIOD", member.period(), start, end,
                    "A janela deste período é desconhecida. Não é possível excluir sobreposição com o ano."));
                continue;
            }
            if (!calendarValid) continue;
            // Half-open windows: touching an anniversary does not count as overlap.
            if (!start.isBefore(until) || !end.isAfter(from)) continue;
            members.add(member);
            if (!periods.add(member.period()) || member.runId() != null && !ids.add(member.runId()))
                issues.add(issue("DUPLICATE_PERIOD", member.period(), start, end,
                    "Selecione apenas uma versão por período e por tentativa."));
            if (start.isBefore(from) || end.isAfter(until))
                issues.add(issue("WINDOW_OUTSIDE_YEAR", member.period(), start, end,
                    "Divida a janela no aniversário do projeto antes de consolidar."));
            if (member.runId() == null)
                issues.add(issue("RUN_MISSING", member.period(), start, end,
                    "Registre a tentativa de cálculo desta janela de monitoramento."));
            else if (member.projectYear() == null || member.projectYear() != year)
                issues.add(issue("RUN_YEAR_MISMATCH", member.period(), start, end,
                    "A tentativa precisa registrar o mesmo ano do projeto e sua janela vigente."));
            if (member.stale())
                issues.add(issue("RUN_STALE", member.period(), start, end,
                    "Reconfirme os vínculos e registre uma nova tentativa; a memória histórica permanece."));
            if (!"REVIEWED_INTERNAL".equals(member.state()) || member.totals() == null)
                issues.add(issue("RUN_NOT_CALCULATED", member.period(), start, end,
                    "Esta janela ainda não tem memória calculada. Ausência não equivale a zero."));
        }

        if (calendarValid) {
            Instant cursor = from;
            var windows = members.stream().filter(m -> m.windowStart() != null && m.windowEndExclusive() != null
                    && m.windowStart().isBefore(m.windowEndExclusive()))
                .sorted(Comparator.comparing(Member::windowStart).thenComparing(Member::period)).toList();
            for (var window : windows) {
                Instant start = window.windowStart().isBefore(from) ? from : window.windowStart();
                Instant end = window.windowEndExclusive().isAfter(until) ? until : window.windowEndExclusive();
                if (start.isAfter(cursor))
                    issues.add(issue("COVERAGE_GAP", window.period(), cursor, start,
                        "Falta uma janela de monitoramento neste intervalo do ano do projeto."));
                else if (start.isBefore(cursor))
                    issues.add(issue("COVERAGE_OVERLAP", window.period(), start, end.isBefore(cursor) ? end : cursor,
                        "Há janelas sobrepostas. A mesma energia não pode entrar duas vezes no ano."));
                if (end.isAfter(cursor)) cursor = end;
            }
            if (cursor.isBefore(until))
                issues.add(issue("COVERAGE_GAP", null, cursor, until,
                    "Falta uma janela de monitoramento até o fim exclusivo do ano do projeto."));
        }

        Totals totals = null;
        if (issues.isEmpty()) {
            BigDecimal be = BigDecimal.ZERO, pe = BigDecimal.ZERO, le = BigDecimal.ZERO, er = BigDecimal.ZERO;
            try {
                for (var member : members) {
                    be = be.add(value(member.totals().beTco2e(), true));
                    pe = pe.add(value(member.totals().peTco2e(), true));
                    le = le.add(value(member.totals().leTco2e(), true));
                    er = er.add(value(member.totals().erTco2e(), false));
                }
                totals = new Totals(number(be), number(pe), number(le), number(er),
                    er.setScale(2, RoundingMode.HALF_EVEN).toPlainString());
            } catch (IllegalArgumentException invalid) {
                issues.add(issue("RUN_MEMORY_INVALID", null, null, null,
                    "A memória de uma janela não contém resultados decimais válidos."));
            }
        }
        String state = totals == null ? "BLOCKED" : "REVIEWED_INTERNAL";
        // Immutable period IDs/hashes and precise values make this read projection reproducible.
        String material = ENGINE + "|" + project + "|" + year + "|" + from + "|" + until
            + "|" + members + "|" + issues + "|" + totals;
        String digest = HexFormat.of().formatHex(AccessSecrets.digest(material));
        return new Result(project, year, from, until, ENGINE, state, false, false,
            totals, List.copyOf(members), List.copyOf(issues), digest);
    }

    private static BigDecimal value(String raw, boolean nonnegative) {
        if (raw == null || raw.length() > 100 || !raw.matches("-?\\d+(\\.\\d+)?"))
            throw new IllegalArgumentException("Resultado ausente ou inválido.");
        var value = new BigDecimal(raw);
        if (nonnegative && value.signum() < 0) throw new IllegalArgumentException("Emissão negativa.");
        return value;
    }

    private static String number(BigDecimal value) {
        return value.signum() == 0 ? "0" : value.stripTrailingZeros().toPlainString();
    }

    private static Issue issue(String code, String period, Instant from, Instant until, String detail) {
        return new Issue(code, period, from, until, detail);
    }
}
