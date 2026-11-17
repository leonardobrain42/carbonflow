package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.List;


import static br.carbonflow.pilot.ListingEvidence.require;
import static br.carbonflow.pilot.PilotSql.first;

final class MonitoringClosure {
    record Closure(String sha, OffsetDateTime effectiveAt, OffsetDateTime endExclusive, List<String> vintages) {}

    static Closure readClosure(Connection connection, String project, DocumentRules.ClosureRef reference) throws SQLException {
        var closure = first(connection, """
                SELECT sha256,convert_from(bytes,'UTF8')::jsonb->>'effectiveAt',
                       convert_from(bytes,'UTF8')::jsonb#>>'{window,endExclusive}',
                       ARRAY(SELECT (cut->>'vintage') || ' · ' || (cut->>'start') || ' / ' || (cut->>'endExclusive')
                             FROM jsonb_array_elements(convert_from(c.bytes,'UTF8')::jsonb#>'{memory,cuts}') WITH ORDINALITY AS items(cut,order_index)
                             ORDER BY order_index)
                FROM pilot_period_closure c WHERE project_id=? AND period=? AND revision=?
                """, row -> new Closure(row.getString(1), OffsetDateTime.parse(row.getString(2)),
                LocalDate.parse(row.getString(3)).atStartOfDay(VintageWindows.ZONE).toOffsetDateTime(), List.of((String[]) row.getArray(4).getArray())),
                project, reference.period(), reference.revision());
        require(closure != null, "FECHAMENTO_PENDENTE", "Consulte o fechamento do período.");
        return closure;
    }

    private MonitoringClosure() {}
}
