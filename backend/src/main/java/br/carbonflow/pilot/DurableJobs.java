package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static br.carbonflow.pilot.PilotSql.*;

@ApplicationScoped
class DurableJobs {
    enum Kind {
        IMPORTACAO("pilot_import_job", "created_at", "('AGUARDANDO','PROCESSANDO')"),
        CALCULO("pilot_selected_run", "proposed_at", "('AGUARDANDO')");

        final String table;
        final String queuedAt;
        final String runnable;

        Kind(String table, String queuedAt, String runnable) {
            this.table = table;
            this.queuedAt = queuedAt;
            this.runnable = runnable;
        }
    }

    record Claim(Kind kind, String project, UUID id, int version, int generation) {}
    record Attempt(int generation, int jobVersion, String state, OffsetDateTime claimedAt, OffsetDateTime expiresAt,
                   OffsetDateTime finishedAt, Integer resultVersion, String reason, UUID eventId) {}
    record Summary(Kind kind, UUID id, String period, int version, String state, int processed, int total, String reason) {}

    private record Identity(String project, UUID id, int version) {}
    private static final class ExpiredLease extends RuntimeException {}

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final Duration lease;

    DurableJobs(PilotDatabase database, PilotAudit audit,
                @ConfigProperty(name = "carbonflow.jobs.lease", defaultValue = "PT3M") Duration lease) {
        this.database = database;
        this.audit = audit;
        this.lease = lease;
        if (lease.toMillis() < 1) throw new IllegalArgumentException("A validade da geração precisa ser positiva.");
    }

    Claim claim(Kind kind) {
        return database.transaction(connection -> {
            var job = first(connection, """
                    SELECT j.project_id,j.id,j.version FROM %s j
                    WHERE j.state IN %s AND NOT EXISTS (
                        SELECT 1 FROM pilot_job_attempt a WHERE a.project_id=j.project_id AND a.job_id=j.id
                          AND a.kind=? AND a.state='EM_EXECUCAO' AND a.expires_at>clock_timestamp())
                    ORDER BY j.%s,j.id LIMIT 1 FOR UPDATE OF j SKIP LOCKED
                    """.formatted(kind.table, kind.runnable, kind.queuedAt),
                    row -> new Identity(row.getString(1), row.getObject(2, UUID.class), row.getInt(3)), kind.name());
            if (job == null) return null;
            // Uma nova consulta após adquirir a linha cobre outro trabalhador que acabou de confirmar a reivindicação.
            if (first(connection, """
                    SELECT true FROM pilot_job_attempt WHERE project_id=? AND kind=? AND job_id=?
                    AND state='EM_EXECUCAO' AND expires_at>clock_timestamp()
                    """, row -> true, job.project(), kind.name(), job.id()) != null) return null;
            update(connection, """
                    UPDATE pilot_job_attempt SET state='EXPIRADA',finished_at=clock_timestamp(),
                        reason='O prazo de execução terminou antes da confirmação.'
                    WHERE project_id=? AND kind=? AND job_id=? AND state='EM_EXECUCAO'
                    """, job.project(), kind.name(), job.id());
            int generation = first(connection, """
                    SELECT coalesce(max(generation),0)+1 FROM pilot_job_attempt WHERE project_id=? AND kind=? AND job_id=?
                    """, row -> row.getInt(1), job.project(), kind.name(), job.id());
            UUID event = audit.record(connection, job.project(), null, "REIVINDICAR_TRABALHO", "PERMITIDO",
                    job.id().toString(), Map.of("kind", kind, "generation", generation, "version", job.version()));
            update(connection, """
                    INSERT INTO pilot_job_attempt(project_id,kind,job_id,generation,job_version,state,claimed_at,expires_at,event_id)
                    SELECT ?,?,?,?,?,'EM_EXECUCAO',now,now+(? * interval '1 millisecond'),?
                    FROM (SELECT clock_timestamp() AS now) instant
                    """, job.project(), kind.name(), job.id(), generation, job.version(), lease.toMillis(), event);
            return new Claim(kind, job.project(), job.id(), job.version(), generation);
        });
    }

    boolean process(Claim claim, PilotDatabase.Query<Void> effect) {
        try {
            return database.transaction(connection -> {
                if (!lockCurrent(connection, claim)) return false;
                effect.execute(connection);
                audit.record(connection, claim.project(), null, "CONFIRMAR_ETAPA_TRABALHO", "PERMITIDO", claim.id().toString(),
                        Map.of("kind", claim.kind(), "generation", claim.generation(), "before", claim.version(), "after", claim.version() + 1));
                finish(connection, claim, "CONFIRMADA", null);
                return true;
            });
        } catch (ExpiredLease expired) {
            return false;
        }
    }

    void fail(Claim claim, String code, String reason) {
        try {
            database.transaction(connection -> {
                if (!lockCurrent(connection, claim)) return null;
                String message = claim.kind() == Kind.IMPORTACAO ? ",message=?" : "";
                String sql = "UPDATE " + claim.kind().table + " SET state='FALHA_RECUPERAVEL',version=version+1"
                        + message + " WHERE project_id=? AND id=?";
                if (claim.kind() == Kind.IMPORTACAO) {
                    update(connection, sql, reason, claim.project(), claim.id());
                } else {
                    update(connection, sql, claim.project(), claim.id());
                }
                audit.record(connection, claim.project(), null, code, "NEGADO", claim.id().toString(),
                        Map.of("version", claim.version() + 1, "generation", claim.generation(), "reason", reason));
                finish(connection, claim, "FALHA_RECUPERAVEL", reason);
                return null;
            });
        } catch (ExpiredLease expired) {
            // Outra geração pode retomar a partir da última versão confirmada.
        }
    }

    private boolean lockCurrent(Connection connection, Claim claim) throws SQLException {
        Integer version = first(connection, "SELECT version FROM " + claim.kind().table
                        + " WHERE project_id=? AND id=? FOR UPDATE",
                row -> row.getInt(1), claim.project(), claim.id());
        return version != null && version == claim.version() && first(connection, """
                SELECT true FROM pilot_job_attempt WHERE project_id=? AND kind=? AND job_id=? AND generation=?
                  AND job_version=? AND state='EM_EXECUCAO' AND expires_at>clock_timestamp()
                """, row -> true, claim.project(), claim.kind().name(), claim.id(), claim.generation(), claim.version()) != null;
    }

    private void finish(Connection connection, Claim claim, String state, String reason) throws SQLException {
        int changed = update(connection, """
                UPDATE pilot_job_attempt SET state=?,finished_at=clock_timestamp(),result_version=?,reason=?
                WHERE project_id=? AND kind=? AND job_id=? AND generation=? AND state='EM_EXECUCAO' AND expires_at>clock_timestamp()
                """, state, claim.version() + 1, reason, claim.project(), claim.kind().name(), claim.id(), claim.generation());
        if (changed != 1) throw new ExpiredLease();
    }

    List<Attempt> history(String project, Kind kind, UUID id) {
        return database.read(connection -> query(connection, """
                SELECT generation,job_version,state,claimed_at,expires_at,finished_at,result_version,reason,event_id
                FROM pilot_job_attempt WHERE project_id=? AND kind=? AND job_id=? ORDER BY generation DESC LIMIT 100
                """, row -> new Attempt(row.getInt(1), row.getInt(2), row.getString(3), row.getObject(4, OffsetDateTime.class),
                row.getObject(5, OffsetDateTime.class), row.getObject(6, OffsetDateTime.class), row.getObject(7, Integer.class),
                row.getString(8), row.getObject(9, UUID.class)), project, kind.name(), id));
    }

    Kind kind(String project, UUID id) {
        return database.read(connection -> first(connection, """
                SELECT 'IMPORTACAO' FROM pilot_import_job WHERE project_id=? AND id=?
                UNION ALL SELECT 'CALCULO' FROM pilot_selected_run WHERE project_id=? AND id=?
                LIMIT 1
                """, row -> Kind.valueOf(row.getString(1)), project, id, project, id));
    }

    List<Summary> list(String project, String period, boolean imports, boolean calculations) {
        return database.read(connection -> query(connection, """
                SELECT kind,id,period,version,state,processed,total,reason FROM (
                    SELECT 'IMPORTACAO' AS kind,id,period,version,state,processed,total,message AS reason,created_at AS at
                    FROM pilot_import_job WHERE ? AND project_id=?
                    UNION ALL
                    SELECT 'CALCULO',id,period,version,state,
                           CASE WHEN snapshot IS NULL THEN 0 ELSE 1 END,1,
                           CASE WHEN state='FALHA_RECUPERAVEL' THEN 'O cálculo pode ser retomado da última revisão.' END,proposed_at
                    FROM pilot_selected_run WHERE ? AND project_id=? AND state<>'PROPOSTA'
                ) j WHERE CAST(? AS text) IS NULL OR period=? ORDER BY at DESC,id LIMIT 100
                """, row -> new Summary(Kind.valueOf(row.getString(1)), row.getObject(2, UUID.class), row.getString(3),
                row.getInt(4), row.getString(5), row.getInt(6), row.getInt(7), row.getString(8)),
                imports, project, calculations, project, period, period));
    }
}
