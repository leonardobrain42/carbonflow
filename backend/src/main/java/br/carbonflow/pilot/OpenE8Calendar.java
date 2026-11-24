package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;

import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Objects;
import java.util.UUID;

final class OpenE8Calendar {
    private static final ZoneId ZONE = VintageWindows.ZONE;
    private static final String BASIS = "VM0038-1.1-CREDITING-START-CALENDAR-YEAR-V1";

    record Window(UUID snapshotId, int dossierVersion, OffsetDateTime start,
                  OffsetDateTime endExclusive) {
        Integer calendarYear() {
            int firstYear = start.atZoneSameInstant(ZONE).getYear();
            var nextYear = LocalDate.of(firstYear + 1, 1, 1).atStartOfDay(ZONE).toInstant();
            return endExclusive.toInstant().isAfter(nextYear) ? null : firstYear;
        }
    }

    record Event(String code, Integer version, LocalDate linkedDate, LocalDate currentDate) {
        boolean current() {
            return version != null && Objects.equals(linkedDate, currentDate);
        }
    }

    record Dates(int applicabilityVersion, Event operationalStart, Event creditingStart) {}

    record Context(String basis, String timeZone, Dates dates, Window window,
                   Integer calendarYear, Integer projectYear, String status) {}

    private OpenE8Calendar() {}

    static Context current(Connection c, String project, String period, UUID binding)
            throws SQLException {
        Window window = binding == null ? first(c, """
            SELECT q.id, d.version, q.period_start, q.period_end
            FROM app_open_mr_dossier_revision d
            JOIN app_open_period_qa_snapshot q ON q.project_id=d.project_id AND q.id=d.snapshot_id
            WHERE d.project_id=? AND d.dossier_code=?
            ORDER BY d.version DESC LIMIT 1
            """, r -> readWindow(r, 1), project, period) : first(c, """
            SELECT q.id, e.dossier_version, q.period_start, q.period_end
            FROM app_open_e8_input_binding_candidate b
            JOIN app_open_e8_energy_candidate e ON e.project_id=b.project_id AND e.id=b.energy_candidate_id
            JOIN app_open_period_qa_snapshot q ON q.project_id=e.project_id AND q.id=e.snapshot_id
            WHERE b.project_id=? AND b.period_code=? AND b.id=?
            """, r -> readWindow(r, 1), project, period, binding);
        Dates dates = first(c, """
            SELECT a.version, a.operational_event_code, op.version, a.operational_event_on, op.event_on,
                   a.vcs_event_code, vcs.version, a.vcs_event_on, vcs.event_on
            FROM app_project_open_applicability_revision a
            LEFT JOIN LATERAL (
              SELECT version,event_on FROM app_project_rights_event
              WHERE project_id=a.project_id AND event_code=a.operational_event_code
                AND kind='OPERATIONAL_START' ORDER BY version DESC LIMIT 1
            ) op ON true
            LEFT JOIN LATERAL (
              SELECT version,event_on FROM app_project_rights_event
              WHERE project_id=a.project_id AND event_code=a.vcs_event_code
                AND kind='VCS_START' ORDER BY version DESC LIMIT 1
            ) vcs ON true
            WHERE a.project_id=? AND a.version=(
              SELECT max(version) FROM app_project_open_applicability_revision WHERE project_id=a.project_id)
              AND a.triage_version=(
              SELECT coalesce(max(version),0) FROM app_project_triage_revision WHERE project_id=a.project_id)
            """, r -> new Dates(r.getInt(1), readEvent(r, 2), readEvent(r, 6)), project);
        return resolve(dates, window);
    }

    static Window readWindow(ResultSet r, int first) throws SQLException {
        return new Window(r.getObject(first, UUID.class), r.getInt(first + 1),
            r.getObject(first + 2, OffsetDateTime.class), r.getObject(first + 3, OffsetDateTime.class));
    }

    private static Event readEvent(ResultSet r, int first) throws SQLException {
        String code = r.getString(first);
        if (code == null) return null;
        return new Event(code, r.getObject(first + 1, Integer.class),
            r.getObject(first + 2, LocalDate.class), r.getObject(first + 3, LocalDate.class));
    }

    static Context resolve(Dates dates, Window window) {
        Integer year = window == null ? null : window.calendarYear();
        Event start = dates == null ? null : dates.operationalStart();
        Event declared = dates == null ? null : dates.creditingStart();
        Integer projectYear = null;
        String status;
        if (window == null) {
            status = "WINDOW_MISSING";
        } else if (year == null) {
            status = "WINDOW_CROSSES_YEAR";
        } else if (start == null || start.linkedDate() == null) {
            status = "CREDITING_START_MISSING";
        } else if (!start.current() || declared != null && !declared.current()) {
            status = "CREDITING_START_REVISED";
        } else if (declared != null && !start.linkedDate().equals(declared.linkedDate())) {
            status = "CREDITING_START_CONFLICT";
        } else if (window.start().atZoneSameInstant(ZONE).toLocalDate().isBefore(start.linkedDate())) {
            status = "WINDOW_BEFORE_CREDITING";
        } else {
            int elapsedYears = year - start.linkedDate().getYear() + 1;
            if (elapsedYears > 100) {
                status = "PROJECT_YEAR_OUT_OF_RANGE";
            } else {
                projectYear = elapsedYears;
                status = "RESOLVED";
            }
        }
        return new Context(BASIS, ZONE.getId(), dates, window, year, projectYear, status);
    }
}
