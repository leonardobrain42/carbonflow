package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;

import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;

import jakarta.enterprise.context.ApplicationScoped;

/** Current annual projection over the canonical immutable period runs. No second run ledger. */
@ApplicationScoped
class OpenE8AnnualConsolidation {
    private final DynamicProjectScope scope;

    OpenE8AnnualConsolidation(DynamicProjectScope scope) { this.scope = scope; }

    OpenE8AnnualMath.Result read(String cookie, String project, int year) {
        return scope.read(cookie, project, "VIEW_OPEN_E8_RUN", (c, context) -> {
            if (year < 1 || year > 100) throw PilotProblem.invalid("E8_YEAR_INVALID", "year", "CF-OPEN-008",
                "Informe um ano do projeto entre 1 e 100.");
            var calendar = OpenE8Timing.calendar(c, project, year);
            var windows = query(c, """
                SELECT DISTINCT ON (d.dossier_code) d.dossier_code,q.period_start,q.period_end
                FROM app_open_mr_dossier_revision d
                JOIN app_open_period_qa_snapshot q ON (q.project_id,q.id)=(d.project_id,d.snapshot_id)
                WHERE d.project_id=? ORDER BY d.dossier_code,d.version DESC
                """, r -> new DossierWindow(r.getString(1), new OpenE8Timing.Window(
                    r.getObject(2, OffsetDateTime.class), r.getObject(3, OffsetDateTime.class))), project);
            var byPeriod = new HashMap<String, OpenE8Timing.Window>();
            for (var window : windows) byPeriod.put(window.period(), window.window());
            var members = new ArrayList<OpenE8AnnualMath.Member>();
            var seen = new HashSet<String>();
            for (var run : OpenE8ProjectRuns.current(c, project)) {
                seen.add(run.period());
                var timing = run.timing();
                var window = timing == null ? byPeriod.get(run.period())
                    : new OpenE8Timing.Window(timing.windowStart(), timing.windowEndExclusive());
                // A replaced MR window must not be hidden by the old run's dates.
                var current = byPeriod.get(run.period());
                if (current != null && !same(window, current)) {
                    members.add(missing(run.period(), current));
                    continue;
                }
                var memory = run.memory();
                members.add(new OpenE8AnnualMath.Member(run.period(), run.id(), run.version(), run.inputSha256(),
                    window == null || window.from() == null ? null : window.from().toInstant(),
                    window == null || window.until() == null ? null : window.until().toInstant(),
                    timing == null ? null : timing.projectYear(), run.state(), run.stale(),
                    memory == null || memory.usableForE8() || run.canIssueVcu() ? null
                        : new OpenE8AnnualMath.Totals(memory.beTco2e(), memory.peTco2e(), memory.leTco2e(),
                            memory.erTco2e(), memory.erDisplay()),
                    run.irContext() == null ? null : new OpenE8AnnualMath.IrReference(run.irContext().assessmentId(),
                        run.irContext().version(), run.irContext().assessmentSha256())));
            }
            for (var window : windows) if (!seen.contains(window.period()))
                members.add(missing(window.period(), window.window()));
            var range = calendar.window();
            return OpenE8AnnualMath.consolidate(project, year,
                range == null ? null : range.from().toInstant(),
                range == null ? null : range.until().toInstant(), calendar.gate(), members);
        });
    }

    private record DossierWindow(String period, OpenE8Timing.Window window) {}

    private static OpenE8AnnualMath.Member missing(String period, OpenE8Timing.Window window) {
        return new OpenE8AnnualMath.Member(period, null, 0, null,
            window.from().toInstant(), window.until().toInstant(), null, "MISSING", false, null, null);
    }

    private static boolean same(OpenE8Timing.Window left, OpenE8Timing.Window right) {
        return left != null && left.from() != null && left.until() != null
            && left.from().toInstant().equals(right.from().toInstant())
            && left.until().toInstant().equals(right.until().toInstant());
    }
}
