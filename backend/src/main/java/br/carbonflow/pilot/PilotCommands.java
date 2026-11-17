package br.carbonflow.pilot;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import com.fasterxml.jackson.databind.JsonNode;
import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

@ApplicationScoped
class PilotCommands {
    private final PilotDatabase database;
    private final PilotAudit audit;

    PilotCommands(PilotDatabase database, PilotAudit audit) {
        this.database = database;
        this.audit = audit;
    }

    static String key(String value) {
        if (value == null || !value.matches("[A-Za-z0-9._-]{1,128}")) {
            throw new PilotProblem(400, "PEDIDO_INVALIDO", "Idempotency-Key", "OP-E6", "Informe uma chave válida.");
        }
        return value;
    }

    static int expected(String etag) {
        if (etag == null) {
            throw new PilotProblem(428, "PRECONDICAO_VERSIONADA_AUSENTE", "If-Match", "OP-E6",
                    "Informe a versão atual do registro.");
        }
        if (!etag.matches("\"(0|[1-9][0-9]{0,8})\"")) {
            throw new PilotProblem(400, "PEDIDO_INVALIDO", "If-Match", "OP-E6", "Informe uma versão entre aspas.");
        }
        return Integer.parseInt(etag.substring(1, etag.length() - 1));
    }

    static void requireVersion(int expected, int current) {
        if (expected != current) {
            throw new PilotProblem(412, "VERSAO_OBSOLETA", "If-Match", "OP-E6", "Recarregue o registro antes de confirmar.");
        }
    }

    JsonNode execute(PilotAccess.Scope scope, String actor, String action, String key, String fingerprint,
                            String target, PilotDatabase.Query<Object> work) {
        try {
            return database.transaction(connection -> {
                first(connection, "SELECT pg_advisory_xact_lock(hashtextextended(?, 0))", row -> true,
                        scope.project() + ":" + action + ":" + key);
                var existing = first(connection, """
                        SELECT actor_id, fingerprint, result FROM pilot_access_command
                        WHERE project_id = ? AND action = ? AND command_key = ?
                        """, row -> {
                    if (!actor.equals(row.getString(1)) || !fingerprint.equals(row.getString(2))) {
                        throw new PilotProblem(409, "CHAVE_REUSADA", "Idempotency-Key", "OP-E6",
                                "A chave já identifica outro comando.");
                    }
                    return audit.decode(row.getString(3));
                }, scope.project(), action, key);
                if (existing != null) {
                    return existing;
                }
                String result = audit.encode(work.execute(connection));
                update(connection, """
                        INSERT INTO pilot_access_command (project_id, action, command_key, actor_id, fingerprint, result)
                        VALUES (?, ?, ?, ?, ?, ?::jsonb)
                        """, scope.project(), action, key, actor, fingerprint, result);
                return audit.decode(result);
            });
        } catch (PilotProblem problem) {
            audit.record(scope.project(), actor, action, "NEGADO", target, Map.of("code", problem.code));
            throw problem;
        }
    }

}
