package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.sql.Connection;
import java.sql.SQLException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.EnergyImportModel.*;
import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class MeasuredSplits {
    static final String CROSSING = "reasons @> '[{\"code\":\"LEITURA_SEM_SEGMENTACAO\"}]'";
    record Input(List<Ref> segments, String proof, String reason) {}
    record Source(Ref row, UUID artifact, String sha256, Fact fact, String period) {}
    record Split(Source original, List<Source> segments, String proof, String reason, String measuredBy, UUID eventId) {}
    private record Raw(Line line, UUID artifact, String sha256, String period, int columns, String state, String receivedBy) {
        Source source() { return new Source(new Ref(line.id(), line.version()), artifact, sha256, line.fact(), period); }
    }

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final EnergyImports imports;
    private final PilotAudit audit;

    MeasuredSplits(PilotDatabase database, PilotCommands commands, ProjectIntake intake, EnergyImports imports, PilotAudit audit) {
        this.database = database;
        this.commands = commands;
        this.intake = intake;
        this.imports = imports;
        this.audit = audit;
    }

    JsonNode reconcile(PilotAccess.Scope scope, String actor, String key, UUID original, int expected, Input input) {
        if (input == null || !IntakeRules.id(input.proof()) || !IntakeRules.text(input.reason()) || input.segments() == null
                || input.segments().size() < 2 || input.segments().size() > 4
                || input.segments().stream().anyMatch(ref -> ref == null || ref.id() == null || ref.version() < 1)
                || input.segments().stream().map(Ref::id).distinct().count() != input.segments().size()) {
            throw invalid("Informe dois a quatro segmentos medidos distintos, suas versões, a referência sintética da medição e o motivo.");
        }
        return commands.execute(scope, actor, "RECONCILIAR_SEGMENTOS_MEDIDOS", key, audit.encode(List.of(original, expected, input)),
                original.toString(), connection -> {
            String project = scope.project();
            var config = intake.lockAndLoad(connection, project);
            var parent = raw(connection, project, original);
            require(List.of("EM_PREVIA", "REJEITADA").contains(parent.line().decision()),
                    "O original deve estar em prévia ou rejeitado como agregado.");
            PilotCommands.requireVersion(expected, parent.line().version());
            MonitoringPeriods.requireIntakeOpen(connection, project, parent.period());
            require(parent.line().reasons().stream().anyMatch(reason -> "LEITURA_SEM_SEGMENTACAO".equals(reason.code()))
                    && parent.line().fact().kwh() != null,
                    "A leitura original deve atravessar uma fronteira e possuir energia de origem.");
            require(details(connection, project, original) == null, "A leitura original já possui segmentos reconciliados.");

            var measured = new ArrayList<Raw>();
            for (var reference : input.segments()) {
                var item = raw(connection, project, reference.id());
                PilotCommands.requireVersion(reference.version(), item.line().version());
                MonitoringPeriods.requireIntakeOpen(connection, project, item.period());
                require("EM_PREVIA".equals(item.line().decision()) && actor.equals(item.receivedBy()) && item.line().fact() != null
                        && "CANDIDATA".equals(EnergyChecks.preview(item.line().original(), item.columns(), config, item.period()).classification()),
                        "O OP deve entregar segmentos medidos e válidos em suas janelas de destino.");
                require(first(connection, "SELECT true FROM pilot_measured_segment WHERE project_id=? AND segment_row=?",
                        row -> true, project, reference.id()) == null, "Um segmento já foi reconciliado com outra leitura.");
                measured.add(item);
            }
            measured.sort(Comparator.comparing(item -> item.line().fact().start().toInstant()));
            reconcile(parent.line().fact(), measured);
            var sources = measured.stream().map(Raw::source).toList();
            UUID event = audit.record(connection, project, actor, "RECONCILIAR_SEGMENTOS_MEDIDOS", "PERMITIDO", original.toString(),
                    java.util.Map.of("proof", input.proof(), "reason", input.reason(), "segments", input.segments()));
            var split = new Split(parent.source(), sources, input.proof(), input.reason(), actor, event);
            update(connection, "INSERT INTO pilot_measured_split VALUES (?,?,?,?::jsonb)", project, original, event, audit.encode(split));
            for (var item : sources) {
                update(connection, "INSERT INTO pilot_measured_segment VALUES (?,?,?)", project, item.row().id(), original);
            }
            return split;
        });
    }

    JsonNode get(String project, UUID original) {
        var value = database.read(connection -> details(connection, project, original));
        if (value == null) throw PilotAccess.invisible();
        return audit.decode(value);
    }

    private String details(Connection connection, String project, UUID original) throws SQLException {
        return first(connection, "SELECT details FROM pilot_measured_split WHERE project_id=? AND original_row=?",
                row -> row.getString(1), project, original);
    }

    private Raw raw(Connection connection, String project, UUID id) throws SQLException {
        var raw = first(connection, """
                SELECT r.*,j.period,j.columns,j.state,f.id AS file_id,f.sha256,f.received_by
                FROM pilot_import_row r JOIN pilot_import_job j ON (j.project_id,j.id)=(r.project_id,r.job_id)
                JOIN pilot_import_file f ON (f.project_id,f.id)=(j.project_id,j.file_id)
                WHERE r.project_id=? AND r.id=?
                """, row -> new Raw(imports.line(row), row.getObject("file_id", UUID.class), row.getString("sha256"),
                row.getString("period"), row.getInt("columns"), row.getString("state"), row.getString("received_by")), project, id);
        if (raw == null) throw PilotAccess.invisible();
        require("CONCLUIDO".equals(raw.state()), "Use linhas de lotes concluídos.");
        return raw;
    }

    private static void reconcile(Fact parent, List<Raw> segments) {
        var cursor = parent.start();
        var total = BigDecimal.ZERO;
        for (var item : segments) {
            var next = item.line().fact();
            require(next.start().isEqual(cursor) && !next.endExclusive().isAfter(parent.endExclusive())
                    && next.kind().equals(parent.kind()) && next.channel().equals(parent.channel())
                    && next.site().equals(parent.site()) && Objects.equals(next.vehicle(), parent.vehicle())
                    && next.meter().equals(parent.meter()), "Os segmentos devem ser contíguos e conservar o contorno da leitura original.");
            cursor = next.endExclusive();
            total = total.add(new BigDecimal(next.kwh()));
        }
        require(cursor.isEqual(parent.endExclusive()) && total.compareTo(new BigDecimal(parent.kwh())) == 0,
                "A união dos intervalos e a soma da energia medida devem corresponder ao original.");
    }

    static void requireReconciled(Connection connection, String project, UUID rowId, Fact fact) throws SQLException {
        Boolean missing = first(connection, """
                SELECT true FROM pilot_import_row r
                WHERE r.project_id=? AND r.id<>? AND r.kind=? AND r.channel=? AND r.vehicle IS NOT DISTINCT FROM ?
                  AND r.start_at<=? AND r.end_at>=? AND
                """ + CROSSING + """
                  AND NOT EXISTS (SELECT 1 FROM pilot_measured_segment s
                      LEFT JOIN pilot_observation_revision v ON (v.project_id,v.row_id)=(s.project_id,s.segment_row)
                      LEFT JOIN pilot_observation_head h ON (h.project_id,h.id,h.version)=(v.project_id,v.id,v.version)
                      WHERE s.project_id=r.project_id AND s.original_row=r.id
                        AND (s.segment_row=? OR (h.id IS NOT NULL AND v.physical_hash=?)))
                LIMIT 1
                """, row -> true, project, rowId, fact.kind(), fact.channel(), fact.vehicle(), fact.start(),
                fact.endExclusive(), rowId, EnergyChecks.hash(fact));
        require(missing == null, "Reconcilie os segmentos medidos com a leitura que cruza a fronteira.");
    }

    private static void require(boolean condition, String message) {
        if (!condition) throw invalid(message);
    }

    private static PilotProblem invalid(String message) {
        return PilotProblem.invalid("SEGMENTACAO_INVALIDA", "segments", "REG-E2-19", message);
    }
}
