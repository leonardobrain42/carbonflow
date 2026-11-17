package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class RetentionPolicy {
    private static final LocalDate FIXTURE_END = LocalDate.of(2032, 9, 30);
    private static final IntakeModel.Ref END_EVIDENCE = new IntakeModel.Ref("SIM-FIM-CREDITO", 1);

    record CreditEnd(int version, LocalDate date, String source, String reason, UUID eventId) {}
    record Correction(LocalDate endsOn, String reason, IntakeModel.Ref proof) {}
    record Category(String name, Long records, LocalDate floorInclusive, String status) {}
    record View(LocalDate asOf, CreditEnd creditEnd, EvidenceQuota.Usage storage, List<String> holds, List<Category> categories) {}

    private final PilotDatabase database;
    private final ProjectIntake intake;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final EvidenceQuota quota;

    RetentionPolicy(PilotDatabase database, ProjectIntake intake, PilotCommands commands, PilotAudit audit, EvidenceQuota quota) {
        this.database = database;
        this.intake = intake;
        this.commands = commands;
        this.audit = audit;
        this.quota = quota;
    }

    static LocalDate floorInclusive(LocalDate creditEnd) {
        return creditEnd.plusYears(2);
    }

    CreditEnd creditEnd(Connection connection, String project, IntakeModel.Operation operation) throws SQLException {
        var revision = first(connection, """
                SELECT version,ends_on,reason,event_id FROM pilot_credit_end_revision
                WHERE project_id=? ORDER BY version DESC LIMIT 1
                """, row -> new CreditEnd(row.getInt(1), row.getObject(2, LocalDate.class), END_EVIDENCE.id(),
                row.getString(3), row.getObject(4, UUID.class)), project);
        if (revision != null) return revision;
        return new CreditEnd(0, operation == null ? FIXTURE_END : operation.creditEndInclusive(),
                operation == null ? "MASSA_SINTETICA" : "COMISSIONAMENTO", null, null);
    }

    View view(String project, LocalDate asOf) {
        return database.transaction(connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var date = asOf == null ? LocalDate.now(VintageWindows.ZONE) : asOf;
            var end = creditEnd(connection, project, intake.latest(connection, project).operation());
            var hold = query(connection, """
                    SELECT 'REVISAO_DOCUMENTAL' WHERE EXISTS (SELECT 1 FROM pilot_document_dossier WHERE project_id=? AND state='EM_REVISAO')
                    UNION ALL SELECT 'AUDITORIA' WHERE EXISTS (
                        SELECT 1 FROM pilot_vvb_audit WHERE project_id=? AND state->>'state' IN ('EM_ANALISE','COM_ACHADO','RESPOSTA_ENVIADA'))
                    UNION ALL SELECT 'DIVERGENCIA_EMISSAO' WHERE EXISTS (SELECT 1 FROM pilot_issuance WHERE project_id=? AND state->>'state'='COM_DIVERGENCIA')
                    UNION ALL SELECT 'RECUPERACAO' WHERE EXISTS (SELECT 1 FROM pilot_recovery_case WHERE project_id=? AND release_event IS NULL)
                    """, row -> row.getString(1), project, project, project, project);
            var floor = floorInclusive(end.date());
            String retained = !hold.isEmpty() ? "GUARDA_POR_PENDENCIA" : date.isAfter(floor) ? "REAVALIAR_RETENCAO" : "GUARDA_MINIMA";
            var categories = query(connection, """
                    SELECT 'MONITORAMENTO', (SELECT count(*) FROM pilot_import_file WHERE project_id=?)
                            + (SELECT count(*) FROM pilot_period_closure WHERE project_id=?)
                    UNION ALL SELECT 'VALIDACAO_VERIFICACAO', (SELECT count(*) FROM pilot_artifact WHERE project_id=?)
                            + (SELECT count(*) FROM pilot_vvb_audit WHERE project_id=?)
                    UNION ALL SELECT 'PROVAS_DE_ATOS', count(*) FROM pilot_listing_external_act WHERE project_id=?
                    """, row -> new Category(row.getString(1), row.getLong(2), floor, retained), project, project, project, project, project);
            var reported = new java.util.ArrayList<>(categories);
            reported.add(new Category("LOG_OPERACIONAL", null, null, "POLITICA_PENDENTE"));
            return new View(date, end, quota.usage(connection, project), hold, List.copyOf(reported));
        });
    }

    JsonNode correct(PilotAccess.Scope scope, String actor, String key, int expected, Correction input) {
        if (input == null || input.endsOn() == null || input.endsOn().getYear() > 9997
                || !IntakeRules.text(input.reason()) || !END_EVIDENCE.equals(input.proof())) {
            throw new PilotProblem(422, "FIM_CREDITO_INCOMPLETO", "endsOn", "RNF-E5-03",
                    "Vincule a prova SIM-FIM-CREDITO v1 e o motivo da correção.");
        }
        return commands.execute(scope, actor, "REVISAR_FIM_CREDITO_RETENCAO", key, audit.encode(List.of(expected, input)), null, connection -> {
            var config = intake.lockAndLoad(connection, scope.project());
            var previous = creditEnd(connection, scope.project(), config.operation());
            PilotCommands.requireVersion(expected, previous.version());
            if (config.operation() == null || input.endsOn().isBefore(config.operation().creditStart())) {
                throw new PilotProblem(409, "FIM_CREDITO_DIVERGENTE", "endsOn", "RNF-E5-03",
                        "Confira o início do período de crédito comissionado.");
            }
            UUID event = audit.record(connection, scope.project(), actor, "REVISAR_FIM_CREDITO_RETENCAO", "PERMITIDO", null,
                    Map.of("before", previous, "after", input, "floorInclusive", floorInclusive(input.endsOn())));
            update(connection, "INSERT INTO pilot_credit_end_revision VALUES (?,?,?,?,?::jsonb,?)", scope.project(),
                    expected + 1, input.endsOn(), input.reason(), audit.encode(input.proof()), event);
            return creditEnd(connection, scope.project(), config.operation());
        });
    }
}
