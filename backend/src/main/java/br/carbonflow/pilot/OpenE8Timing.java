package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.HexFormat;
import java.util.UUID;

final class OpenE8Timing {
    record Window(OffsetDateTime from, OffsetDateTime until) {}
    record Year(Integer projectYear, Integer sourceYear, String gate) {}
    record Context(String fingerprint, Window window, Year year) {}

    record Calendar(LocalDate creditStart, Window window, String gate) {}

    private record Credit(Declaration declaration, OpenProjectRights.Event operational,
                          OpenProjectRights.Event credit, String gate) {}

    private record Declaration(int triageVersion, String operationalCode, LocalDate operationalDate,
                               String creditCode, LocalDate creditDate) {}

    private OpenE8Timing() {}

    static Context resolve(Connection connection, String project, UUID binding) throws SQLException {
        Window window = binding == null ? null : first(connection, """
            SELECT q.period_start, q.period_end
            FROM app_open_e8_input_binding_candidate b
            JOIN app_open_e8_energy_candidate e ON (e.project_id,e.id)=(b.project_id,b.energy_candidate_id)
            JOIN app_open_period_qa_snapshot q ON (q.project_id,q.id)=(e.project_id,e.snapshot_id)
            WHERE b.project_id=? AND b.id=?
            """, row -> new Window(row.getObject(1, OffsetDateTime.class),
                row.getObject(2, OffsetDateTime.class)), project, binding);
        if (window == null)
            return new Context(fingerprint("MISSING_WINDOW"), null, new Year(null, null, "RUN_WINDOW_MISSING"));

        Credit context = credit(connection, project);
        var declaration = context.declaration();
        var operational = context.operational();
        var credit = context.credit();
        String gate = context.gate();
        Year year = gate == null ? years(credit.eventOn(), window) : new Year(null, null, gate);
        String digest = fingerprint("OPEN-E8-TIMING-V1|" + window.from().toInstant() + "|"
            + window.until().toInstant() + "|" + declaration + "|" + eventKey(operational)
            + "|" + eventKey(credit) + "|" + year);
        return new Context(digest, window, year);
    }

    /** Same reviewed start and Brazilian anniversaries used by period runs. */
    static Calendar calendar(Connection connection, String project, int year) throws SQLException {
        if (year < 1 || year > 100) throw new IllegalArgumentException("Ano do projeto fora do contrato.");
        Credit context = credit(connection, project);
        if (context.gate() != null) return new Calendar(null, null, context.gate());
        LocalDate start = context.credit().eventOn();
        return new Calendar(start, yearWindow(start, year), null);
    }

    static Window yearWindow(LocalDate start, int year) {
        if (start == null || year < 1 || year > 100) throw new IllegalArgumentException("Calendário inválido.");
        return new Window(
            start.plusYears(year - 1L).atStartOfDay(VintageWindows.ZONE).toOffsetDateTime(),
            start.plusYears(year).atStartOfDay(VintageWindows.ZONE).toOffsetDateTime());
    }

    private static Credit credit(Connection connection, String project) throws SQLException {
        Declaration declaration = first(connection, """
            SELECT triage_version, operational_event_code, operational_event_on, vcs_event_code, vcs_event_on
            FROM app_project_open_applicability_revision WHERE project_id=?
            ORDER BY version DESC LIMIT 1
            """, row -> new Declaration(row.getInt(1), row.getString(2), row.getObject(3, LocalDate.class),
                row.getString(4), row.getObject(5, LocalDate.class)), project);

        OpenProjectRights.Event operational = null;
        OpenProjectRights.Event credit = null;
        String gate = null;
        if (declaration == null || declaration.operationalCode() == null || declaration.creditCode() == null) {
            gate = "CREDIT_START_MISSING";
        } else {
            operational = OpenProjectRights.latestEvent(connection, project, declaration.operationalCode());
            credit = OpenProjectRights.latestEvent(connection, project, declaration.creditCode());
            if (declaration.triageVersion() != OpenCalculationReadiness.triageRevision(connection, project)
                    || !matches(operational, "OPERATIONAL_START", declaration.operationalDate())
                    || !matches(credit, "VCS_START", declaration.creditDate())) {
                gate = "CREDIT_START_RECHECK";
            } else if (!operational.eventOn().equals(credit.eventOn())) {
                gate = "CREDIT_START_MISMATCH";
            }
        }
        return new Credit(declaration, operational, credit, gate);
    }

    static Year years(LocalDate creditStart, Window window) {
        // Use the Brazilian civil calendar for credit anniversaries. The
        // existing OPEN fleet catalogue uses UTC observation-year cutoffs.
        LocalDate first = window.from().atZoneSameInstant(VintageWindows.ZONE).toLocalDate();
        var lastInstant = window.until().toInstant().minusNanos(1);
        LocalDate last = lastInstant.atZone(VintageWindows.ZONE).toLocalDate();
        if (first.isBefore(creditStart))
            return new Year(null, null, "WINDOW_BEFORE_CREDIT_START");

        int year = 1 + first.getYear() - creditStart.getYear();
        if (first.isBefore(creditStart.plusYears(year - 1L))) year--;
        if (year < 1 || year > 100 || !last.isBefore(creditStart.plusYears(year)))
            return new Year(null, null, "WINDOW_PROJECT_YEAR_BOUNDARY");

        int sourceYear = window.from().withOffsetSameInstant(ZoneOffset.UTC).getYear();
        if (lastInstant.atOffset(ZoneOffset.UTC).getYear() != sourceYear)
            return new Year(null, null, "WINDOW_SOURCE_YEAR_BOUNDARY");
        return new Year(year, sourceYear, null);
    }

    private static boolean matches(OpenProjectRights.Event event, String kind, LocalDate recorded) {
        return event != null && kind.equals(event.kind()) && event.eventOn().equals(recorded);
    }

    private static String eventKey(OpenProjectRights.Event event) {
        return event == null ? "-" : event.code() + ":" + event.version() + ":" + event.eventOn();
    }

    private static String fingerprint(String material) {
        return HexFormat.of().formatHex(AccessSecrets.digest(material));
    }
}
