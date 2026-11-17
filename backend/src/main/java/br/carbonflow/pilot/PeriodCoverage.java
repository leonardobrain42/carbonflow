package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static br.carbonflow.pilot.PilotSql.*;

final class PeriodCoverage {
    record Finding(String code, String channel, OffsetDateTime from, OffsetDateTime until, UUID row) {}
    record Report(boolean complete, int findings, List<Finding> examples, int expectedChannels) {}
    private record Channel(String kind, String id) {}
    private static final int MAX_EXAMPLES = 50;

    static Report assess(Connection connection, String project, String period, UUID selection,
                         VintageWindows.Window window, IntakeModel.Snapshot config) throws SQLException {
        var inventory = config.intake().candidate().inventory();
        Map<Channel, OffsetDateTime> cursor = new LinkedHashMap<>();
        inventory.stations().forEach(station -> cursor.put(new Channel("ED", station.id()), window.opening()));
        inventory.ecCircuits().forEach(circuit -> cursor.put(new Channel("EC", circuit.id()), window.opening()));
        var findings = new ArrayList<Finding>();
        int total = 0;
        try (var statement = connection.prepareStatement("""
                SELECT v.kind,v.channel,v.start_at,v.end_at,r.id,r.original,j.columns
                FROM pilot_selection_entry e
                JOIN pilot_observation_revision v ON (v.project_id,v.id,v.version)=(e.project_id,e.observation_id,e.observation_version)
                JOIN pilot_import_row r ON (r.project_id,r.id)=(v.project_id,v.row_id)
                JOIN pilot_import_job j ON (j.project_id,j.id)=(r.project_id,r.job_id)
                WHERE e.project_id=? AND e.snapshot_id=?
                ORDER BY v.kind,v.channel,v.start_at,v.id
                """)) {
            statement.setString(1, project);
            statement.setObject(2, selection);
            statement.setFetchSize(500);
            try (var rows = statement.executeQuery()) {
                while (rows.next()) {
                    var channel = new Channel(rows.getString("kind"), rows.getString("channel"));
                    var start = rows.getObject("start_at", OffsetDateTime.class);
                    var end = rows.getObject("end_at", OffsetDateTime.class);
                    var previous = cursor.get(channel);
                    var proof = EnergyChecks.preview(rows.getString("original"), rows.getInt("columns"), config, period);
                    if (previous == null || !"CANDIDATA".equals(proof.classification())) {
                        total += add(findings, new Finding("LEITURA_SEM_PROVA_ATUAL", channel.id(), start, end,
                                rows.getObject("id", UUID.class)));
                        continue;
                    }
                    if (start.isAfter(previous)) {
                        total += add(findings, new Finding("INTERVALO_AUSENTE", channel.id(), previous, start, null));
                    } else if (start.isBefore(previous)) {
                        total += add(findings, new Finding("INTERVALO_SOBREPOSTO", channel.id(), start, previous, null));
                    }
                    if (end.isAfter(previous)) cursor.put(channel, end);
                }
            }
        }
        for (var entry : cursor.entrySet()) {
            if (entry.getValue().isBefore(window.ending())) {
                total += add(findings, new Finding("INTERVALO_AUSENTE", entry.getKey().id(),
                        entry.getValue(), window.ending(), null));
            }
        }

        var pending = query(connection, """
                SELECT r.id,r.channel,r.start_at,r.end_at,count(*) OVER() AS amount
                FROM pilot_import_row r JOIN pilot_import_job j ON (j.project_id,j.id)=(r.project_id,r.job_id)
                LEFT JOIN pilot_measured_split s ON (s.project_id,s.original_row)=(r.project_id,r.id)
                WHERE r.project_id=? AND (j.period=? OR (r.start_at<? AND r.end_at>?
                      AND r.reasons @> '[{"code":"LEITURA_SEM_SEGMENTACAO"}]')) AND r.decision IN ('EM_PREVIA','PROPOSTA')
                  AND s.original_row IS NULL
                ORDER BY r.job_id,r.line LIMIT ?
                """, row -> Map.entry(row.getInt("amount"), new Finding("LINHA_SEM_DECISAO", row.getString("channel"),
                row.getObject("start_at", OffsetDateTime.class), row.getObject("end_at", OffsetDateTime.class),
                row.getObject("id", UUID.class))), project, period, window.ending(), window.opening(), MAX_EXAMPLES - findings.size() + 1);
        if (!pending.isEmpty()) {
            total += pending.getFirst().getKey();
            for (var item : pending) add(findings, item.getValue());
        }
        if (first(connection, """
                SELECT true FROM pilot_import_job WHERE project_id=? AND period=?
                AND state IN ('AGUARDANDO','PROCESSANDO','FALHA_RECUPERAVEL') LIMIT 1
                """, row -> true, project, period) != null) {
            total += add(findings, new Finding("IMPORTACAO_INCOMPLETA", null, null, null, null));
        }
        return new Report(total == 0, total, List.copyOf(findings), cursor.size());
    }

    private static int add(List<Finding> findings, Finding finding) {
        if (findings.size() < MAX_EXAMPLES) findings.add(finding);
        return 1;
    }
}
