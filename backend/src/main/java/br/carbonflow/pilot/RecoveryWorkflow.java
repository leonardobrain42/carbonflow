package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.fasterxml.jackson.databind.JsonNode;
import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class RecoveryWorkflow {
    record Review(String gapSha, String reason) {}
    record Run(UUID id, String period, String sha256, boolean verified) {}
    record Effect(String kind, String source, String namespace, String reference, String status, JsonNode binding) {}
    record Recovery(UUID id, String manifestSha, String gapSha, OffsetDateTime checkpoint, OffsetDateTime observedThrough,
                    OffsetDateTime incidentAt, OffsetDateTime openedAt, String checkedBy, UUID checkEvent, UUID releaseEvent,
                    OffsetDateTime releasedAt) {
        int version() { return releasedAt != null ? 3 : checkedBy != null ? 2 : 1; }
    }
    record View(Recovery recovery, int version, List<Run> runs, List<Effect> effects, int unsettledJobs, long rpoSeconds, Long rtoSeconds) {}

    private final PilotDatabase database;
    private final PilotCommands commands;
    private final PilotAudit audit;
    private final ProjectIntake intake;

    RecoveryWorkflow(PilotDatabase database, PilotCommands commands, PilotAudit audit, ProjectIntake intake) {
        this.database = database;
        this.commands = commands;
        this.audit = audit;
        this.intake = intake;
    }

    static boolean isOpen(Connection connection, String project) throws SQLException {
        return first(connection, "SELECT true FROM pilot_recovery_case WHERE project_id=? AND release_event IS NULL",
                row -> true, project) != null;
    }

    static void requireIssuanceOpen(Connection connection, String project) throws SQLException {
        if (isOpen(connection, project)) {
            throw problem("RECUPERACAO_PENDENTE",
                    "Conclua a conferência da recuperação antes de preparar ou protocolar nova emissão.");
        }
    }

    private record ObservedAct(String digest, String binding) {}

    static void requireObservedAct(Connection connection, String project, String authority, String namespace,
                            String officialId, String digest) throws SQLException {
        if (!isOpen(connection, project)) return;
        var expected = requireObserved(connection, project, authority, namespace, officialId);
        if (!digest.equals(expected.digest())) {
            throw problem("PROVA_INTERVALO_DIVERGENTE", "O conteúdo da prova diverge do inventário recuperado.");
        }
    }

    UUID requireObservedProtocol(Connection connection, String project, String officialId, String registry,
                                 String account, String target, UUID predecessor, List<IssuanceModel.Requested> requested) throws SQLException {
        var expected = requireObserved(connection, project, "PP", "SIM-RECIBOS-EMISSAO", officialId);
        var lines = requested.stream().map(line -> new IssuanceModel.Line(line.vintage(), line.amount()))
                .sorted(java.util.Comparator.comparing(IssuanceModel.Line::vintage, IssuanceModel.BY_VINTAGE)).toList();
        var id = first(connection, """
                SELECT saved."attemptId" FROM jsonb_to_record(?::jsonb) AS
                    saved(verification text,account text,target text,predecessor uuid,lines jsonb,"attemptId" uuid)
                WHERE saved.verification=? AND saved.account=? AND (CAST(? AS text) IS NULL OR saved.target=?)
                    AND saved.predecessor IS NOT DISTINCT FROM ? AND saved.lines=?::jsonb
                """, row -> row.getObject(1, UUID.class), expected.binding(), registry, account, target, target, predecessor, audit.encode(lines));
        if (id == null) {
            throw problem("PEDIDO_INTERVALO_DIVERGENTE", "Confira registro, conta, recortes e quantidades do protocolo observado.");
        }
        return id;
    }

    private static ObservedAct requireObserved(Connection connection, String project, String authority, String namespace,
                                               String officialId) throws SQLException {
        var expected = first(connection, """
                SELECT e.digest,e.binding FROM pilot_recovery_case c JOIN pilot_recovery_effect e
                  ON (e.project_id,e.recovery)=(c.project_id,c.id)
                WHERE c.project_id=? AND c.release_event IS NULL AND e.kind='ATO_EXTERNO'
                    AND (e.source,e.namespace,e.reference)=(?,?,?)
                """, row -> new ObservedAct(row.getString(1), row.getString(2)), project, authority, namespace, officialId);
        if (expected == null) throw problem("PROVA_FORA_INTERVALO", "O ato não consta do intervalo aguardando conferência.");
        return expected;
    }

    View current(String project) {
        return database.transaction(connection -> {
            connection.setTransactionIsolation(Connection.TRANSACTION_REPEATABLE_READ);
            var current = find(connection, project, null);
            return current == null ? null : view(connection, project, current);
        });
    }

    JsonNode review(PilotAccess.Scope scope, String actor, String key, UUID id, int version, Review input, boolean release) {
        if (input == null || !IntakeRules.text(input.reason()) || input.gapSha() == null || !input.gapSha().matches("[0-9a-f]{64}")) {
            throw problem("CONFERENCIA_INCOMPLETA", "Informe o hash do inventário do intervalo e a fundamentação.");
        }
        String action = release ? "LIBERAR_RECUPERACAO" : "CONFERIR_INTERVALO_RECUPERACAO";
        return commands.execute(scope, actor, action, key, audit.encode(List.of(id, version, input)), id.toString(), connection -> {
            String project = scope.project();
            intake.lockProject(connection, project);
            var old = find(connection, project, id);
            if (old == null) throw PilotAccess.invisible();
            PilotCommands.requireVersion(version, old.version());
            if (old.version() != (release ? 2 : 1) || !old.gapSha().equals(input.gapSha())) {
                throw problem("CONFERENCIA_DIVERGENTE", "Confira o intervalo preservado e a versão da recuperação.");
            }
            if (release && actor.equals(old.checkedBy())) throw PilotAccess.forbidden();
            var progress = view(connection, project, old);
            if (progress.runs().stream().anyMatch(run -> !run.verified())) {
                throw problem("REEXECUCAO_PENDENTE", "Reexecute os cálculos fechados e confira seus hashes no ambiente restaurado.");
            }
            if (progress.effects().stream().anyMatch(effect -> !"CONFERIDO".equals(effect.status()))) {
                throw problem("INTERVALO_PENDENTE", "Concilie as provas e reponha os brutos do intervalo antes de liberar.");
            }
            if (progress.unsettledJobs() > 0) {
                throw problem("TRABALHO_PENDENTE", "Aguarde os trabalhos em curso ou retome a partir da trilha de falha.");
            }
            UUID event = audit.record(connection, project, actor, action, "PERMITIDO", id.toString(),
                    Map.of("manifestSha", old.manifestSha(), "gapSha", old.gapSha(), "observedThrough", old.observedThrough(),
                            "before", old.version(), "after", old.version() + 1, "reason", input.reason()));
            if (release) {
                update(connection, """
                        UPDATE pilot_recovery_case SET release_event=?,released_at=clock_timestamp() WHERE project_id=? AND id=?
                        """, event, project, id);
            } else {
                update(connection, "UPDATE pilot_recovery_case SET checked_by=?,check_event=? WHERE project_id=? AND id=?",
                        actor, event, project, id);
            }
            return view(connection, project, find(connection, project, id));
        });
    }

    private static Recovery find(Connection connection, String project, UUID id) throws SQLException {
        return first(connection, """
                SELECT id,manifest_sha,gap_sha,checkpoint,observed_through,incident_at,opened_at,
                       checked_by,check_event,release_event,released_at
                FROM pilot_recovery_case WHERE project_id=? AND (CAST(? AS uuid) IS NULL OR id=?)
                ORDER BY opened_at DESC,id DESC LIMIT 1
                """, row -> new Recovery(row.getObject(1, UUID.class), row.getString(2), row.getString(3),
                row.getObject(4, OffsetDateTime.class), row.getObject(5, OffsetDateTime.class), row.getObject(6, OffsetDateTime.class),
                row.getObject(7, OffsetDateTime.class), row.getString(8), row.getObject(9, UUID.class), row.getObject(10, UUID.class),
                row.getObject(11, OffsetDateTime.class)), project, id, id);
    }

    private View view(Connection connection, String project, Recovery recovery) throws SQLException {
        var runs = query(connection, """
                SELECT x.run_id,r.period,x.snapshot_sha,EXISTS (
                    SELECT 1 FROM pilot_access_event e WHERE e.project_id=x.project_id AND e.target=x.run_id::text
                      AND e.action='REEXECUTAR_SNAPSHOT' AND e.outcome='PERMITIDO' AND e.occurred_at>=?
                      AND e.details->>'snapshotSha256'=x.snapshot_sha AND e.details->>'matches'='true')
                FROM pilot_recovery_run x JOIN pilot_selected_run r ON (r.project_id,r.id)=(x.project_id,x.run_id)
                WHERE x.project_id=? AND x.recovery=? ORDER BY r.period,x.run_id
                """, row -> new Run(row.getObject(1, UUID.class), row.getString(2), row.getString(3), row.getBoolean(4)),
                recovery.openedAt(), project, recovery.id());
        var effects = query(connection, """
                SELECT e.kind,e.source,e.namespace,e.reference,
                    CASE
                        WHEN a.reference IS NULL AND e.kind IN ('BRUTO','FATO_ED_EC') THEN 'A_REPOR'
                        WHEN a.reference IS NULL THEN 'A_CAPTURAR'
                        WHEN a.digest<>e.digest OR a.binding IS DISTINCT FROM e.binding THEN 'HASH_DIVERGENTE'
                        WHEN e.namespace IN ('SIM-VERRA-DEPOSITO','SIM-VERRA-AJUSTE')
                             AND (i.state->>'state' IS DISTINCT FROM 'CONCILIADO') THEN 'A_CONCILIAR'
                        WHEN e.namespace='SIM-REGISTRY-POSICAO' AND EXISTS (
                            SELECT 1 FROM pilot_position_conflict conflict
                            JOIN pilot_verification_position p ON (p.project_id,p.id)=(conflict.project_id,conflict.position_id)
                            WHERE conflict.project_id=e.project_id AND conflict.verification=a.subject
                              AND NOT EXISTS (SELECT 1 FROM pilot_verification_position newer
                                  WHERE (newer.project_id,newer.verification,newer.start_on,newer.end_before)
                                      =(p.project_id,p.verification,p.start_on,p.end_before)
                                    AND (newer.state->>'historical')::boolean=false
                                    AND (newer.as_of>p.as_of OR newer.as_of=p.as_of AND newer.official_order>p.official_order))
                        ) THEN 'POSICAO_DIVERGENTE'
                        ELSE 'CONFERIDO'
                    END, e.binding
                FROM pilot_recovery_effect e
                LEFT JOIN pilot_recovery_observed_effect a ON
                    (a.project_id,a.kind,a.source,a.namespace,a.reference)=(e.project_id,e.kind,e.source,e.namespace,e.reference)
                LEFT JOIN pilot_issuance i ON (i.project_id,i.id)=(a.project_id,a.subject)
                WHERE e.project_id=? AND e.recovery=? ORDER BY e.kind,e.source,e.namespace,e.reference
                """, row -> new Effect(row.getString(1), row.getString(2), row.getString(3), row.getString(4), row.getString(5),
                        row.getString(6) == null ? null : audit.decode(row.getString(6))), project, recovery.id());
        int unsettledJobs = first(connection, """
                SELECT (SELECT count(*) FROM pilot_import_job WHERE project_id=?
                             AND state IN ('AGUARDANDO','PROCESSANDO','FALHA_RECUPERAVEL'))
                     + (SELECT count(*) FROM pilot_selected_run WHERE project_id=?
                             AND state IN ('AGUARDANDO','FALHA_RECUPERAVEL'))
                """, row -> row.getInt(1), project, project);
        long rpo = Duration.between(recovery.checkpoint(), recovery.incidentAt()).toSeconds();
        Long rto = recovery.releasedAt() == null ? null : Duration.between(recovery.incidentAt(), recovery.releasedAt()).toSeconds();
        return new View(recovery, recovery.version(), runs, effects, unsettledJobs, rpo, rto);
    }

    private static PilotProblem problem(String code, String message) {
        return new PilotProblem(409, code, "recovery", "CT-E5-31", message);
    }
}
