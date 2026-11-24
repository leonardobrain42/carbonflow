package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

/** Exploratory twelve months before credit start. Not an interpretation admitted for Eq. 8. */
final class OpenDyFirstYear {
    static final String POLICY="FIRST_YEAR_EXPLORATORY@1";
    record Context(LocalDate creditStart,LocalDate exploratoryFrom,LocalDate exploratoryUntilExclusive,
                   String contextGate,String policyVersion) {
        OffsetDateTime from() {
            return exploratoryFrom==null?null:exploratoryFrom.atStartOfDay(VintageWindows.ZONE).toOffsetDateTime();
        }
        OffsetDateTime until() {
            return exploratoryUntilExclusive==null?null:exploratoryUntilExclusive.atStartOfDay(VintageWindows.ZONE).toOffsetDateTime();
        }
    }
    private OpenDyFirstYear() {}

    static Context current(Connection c,String project) throws SQLException {
        var calendar=OpenE8Timing.calendar(c,project,1);
        if (calendar.gate()!=null) return unresolved(calendar.gate());
        var readiness=OpenApplicability.currentReadiness(c,project);
        if (readiness.stale() || !"OPEN_CANDIDATE".equals(readiness.network())
                || !"OPEN_LDV_DCFC_SIN_BR_CANDIDATE".equals(readiness.category()))
            return unresolved("OPEN_BRANCH_REVIEW_REQUIRED");
        return exploratory(calendar.creditStart());
    }
    static Context exploratory(LocalDate start) {
        return new Context(start,start.minusYears(1),start,null,POLICY);
    }
    private static Context unresolved(String gate) {
        return new Context(null,null,null,gate,POLICY);
    }
    static void store(Connection c,UUID assessment,Context context) throws SQLException {
        update(c,"""
            INSERT INTO app_open_dy_first_year_context
            (assessment_id,credit_start,exploratory_from,exploratory_until_exclusive,context_gate,policy_version)
            VALUES (?,?,?,?,?,?)
            """,assessment,context.creditStart(),context.exploratoryFrom(),context.exploratoryUntilExclusive(),
            context.contextGate(),context.policyVersion());
    }
    static Context read(Connection c,UUID assessment) throws SQLException {
        return first(c,"""
            SELECT credit_start,exploratory_from,exploratory_until_exclusive,context_gate,policy_version
            FROM app_open_dy_first_year_context WHERE assessment_id=?
            """,r->new Context(r.getObject(1,LocalDate.class),r.getObject(2,LocalDate.class),
            r.getObject(3,LocalDate.class),r.getString(4),r.getString(5)),assessment);
    }
}
