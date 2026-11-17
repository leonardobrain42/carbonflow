package br.carbonflow.pilot;

import java.sql.Connection;
import java.sql.SQLException;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import static br.carbonflow.pilot.PilotSql.first;

@ApplicationScoped
class EvidenceQuota {
    record Usage(long usedBytes, long limitBytes) {}

    private final long limit;

    EvidenceQuota(@ConfigProperty(name = "carbonflow.evidence.max-bytes", defaultValue = "2000000000") long limit) {
        this.limit = limit;
        if (limit < 1) throw new IllegalArgumentException("A cota de evidências precisa ser positiva.");
    }

    Usage usage(Connection connection, String project) throws SQLException {
        long stored = first(connection, """
                SELECT (SELECT coalesce(sum(octet_length(content)),0) FROM pilot_artifact WHERE project_id=?)
                     + (SELECT coalesce(sum(octet_length(bytes)),0) FROM pilot_import_file WHERE project_id=?)
                     + (SELECT coalesce(sum(octet_length(manifest)),0) FROM pilot_document_variant WHERE project_id=?)
                """, row -> row.getLong(1), project, project, project);
        return new Usage(stored, limit);
    }

    // Leitura e gravação compartilham o bloqueio do projeto na transação do produtor.
    void requireSpace(Connection connection, String project, long incoming) throws SQLException {
        var usage = usage(connection, project);
        if (incoming > usage.limitBytes() - usage.usedBytes()) {
            throw new PilotProblem(409, "LIMITE_DE_CAPACIDADE", "evidence", "RF-E5-22",
                    "A cota compartilhada de evidências está cheia. Preserve os bytes da nova entrega na origem.");
        }
    }
}
