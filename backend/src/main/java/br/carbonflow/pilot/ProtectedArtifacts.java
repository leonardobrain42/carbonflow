package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import com.fasterxml.jackson.databind.JsonNode;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

@ApplicationScoped
class ProtectedArtifacts {
    static final String SAMPLE = "E8-002@1";

    record Reference(UUID id, int version) {
        @Override
        public String toString() {
            return id + "@" + version;
        }
    }
    record Artifact(UUID id, int version, UUID dossierId, String variant, String purpose, String title,
                    String author, String approvedBy, String state, String sha256, String mediaType, String edition) {}
    record Preview(Artifact artifact, String text) {}
    record Download(byte[] content, String mediaType) {}
    record Receipt(String issuer, String capturedBy, OffsetDateTime capturedAt) {}
    record Grant(UUID id, int version, Reference artifact, String purpose, String recipient, String grantedBy,
                 OffsetDateTime grantedAt, OffsetDateTime expiresAt, OffsetDateTime revokedAt, Receipt receipt) {}

    private record Visible(Artifact view, byte[] bytes, UUID grant) {}

    private static final String VISIBLE = """
            FROM pilot_artifact a
            LEFT JOIN LATERAL (
                SELECT g.id
                FROM pilot_grant g
                WHERE g.project_id = a.project_id AND g.artifact_id = a.id AND g.artifact_version = a.version
                  AND g.purpose = a.purpose AND g.recipient_id = ?
                  AND g.granted_at <= statement_timestamp()
                  AND g.revoked_at IS NULL AND g.expires_at > statement_timestamp()
                ORDER BY g.granted_at DESC, g.id LIMIT 1
            ) active ON true
            WHERE a.project_id = ?
              AND (? OR (a.approved_at IS NOT NULL AND (? OR active.id IS NOT NULL)))
            """;
    private static final String GRANTS = """
            SELECT g.*, r.issuer, r.captured_by, r.captured_at
            FROM pilot_grant g LEFT JOIN pilot_receipt r ON r.project_id = g.project_id AND r.grant_id = g.id
            WHERE g.project_id = ?
            """;

    private final PilotDatabase database;
    private final PilotAudit audit;
    private final PilotCommands commands;
    private final ProjectIntake intake;
    private final EvidenceQuota quota;

    ProtectedArtifacts(PilotDatabase database, PilotAudit audit, PilotCommands commands, ProjectIntake intake, EvidenceQuota quota) {
        this.database = database;
        this.audit = audit;
        this.commands = commands;
        this.intake = intake;
        this.quota = quota;
    }

    List<Artifact> search(PilotAccess.Scope scope, String actor, String term) {
        return database.transaction(connection -> {
            String sql = "SELECT a.* " + VISIBLE + """
                     AND strpos(lower(a.title), lower(?)) > 0
                     ORDER BY a.created_at DESC, a.title, a.version DESC, a.id LIMIT 100
                    """;
            var rows = query(connection, sql, row -> artifact(row, scope), actor, scope.project(),
                    drafts(scope), scope.has(PilotAccess.PP), term);
            audit.record(connection, scope.project(), actor, "BUSCAR_ARTEFATOS", "PERMITIDO", null, Map.of());
            return rows;
        });
    }

    Preview preview(PilotAccess.Scope scope, String actor, Reference reference) {
        var visible = readArtifact(scope, actor, reference, "PREVER_ARTEFATO");
        String text = "application/zip".equals(visible.view().mediaType())
                ? "SIMULAÇÃO · Dossiê congelado · " + visible.view().variant() + " · " + visible.view().edition()
                : new String(visible.bytes(), StandardCharsets.UTF_8);
        return new Preview(visible.view(), text);
    }

    Download download(PilotAccess.Scope scope, String actor, Reference reference) {
        var visible = readArtifact(scope, actor, reference, "BAIXAR_ARTEFATO");
        return new Download(visible.bytes(), visible.view().mediaType());
    }

    private Visible readArtifact(PilotAccess.Scope scope, String actor, Reference reference, String action) {
        var result = database.transaction(connection -> {
            String sql = "SELECT a.*, active.id AS access_grant " + VISIBLE + " AND a.id = ? AND a.version = ?"
                    + " AND (a.media_type <> 'application/zip' OR active.id IS NOT NULL OR ?)";
            var visible = first(connection, sql, row -> new Visible(artifact(row, scope), row.getBytes("content"),
                    row.getObject("access_grant", UUID.class)), actor, scope.project(), drafts(scope),
                    scope.has(PilotAccess.PP), reference.id(), reference.version(), !"BAIXAR_ARTEFATO".equals(action));
            audit.record(connection, scope.project(), actor, action, visible == null ? "NEGADO" : "PERMITIDO",
                    reference.toString(), visible != null && visible.grant() != null && scope.has(PilotAccess.VVB)
                            ? Map.of("grantId", visible.grant(), "purpose", visible.view().purpose())
                            : Map.of());
            return visible;
        });
        if (result == null) {
            throw PilotAccess.invisible();
        }
        return result;
    }

    JsonNode createDossier(PilotAccess.Scope scope, String actor, String key) {
        return commands.execute(scope, actor, "PREPARAR_DOSSIE", key, SAMPLE, null, connection -> {
            record Sample(UUID artifact, int version, String variant, String title, byte[] bytes) {}
            intake.lockProject(connection, scope.project());
            UUID dossier = UUID.randomUUID();
            var samples = new java.util.ArrayList<Sample>();
            for (String variant : List.of("PUBLICA", "RESERVADA")) {
                UUID artifact = UUID.randomUUID();
                for (int version = 1; version <= 2; version++) {
                    String title = variant.equals("PUBLICA") ? "Memória pública de ensaio" : "Anexo reservado de ensaio";
                    String text = "SIMULAÇÃO · PIL-SYN · " + title + "\nVersão " + version
                            + "\nFinalidade: auditoria V1\nCenário sintético de monitoramento.\n";
                    if (variant.equals("RESERVADA")) {
                        text += "Referência reservada fictícia: SIM-ANEXO-RESERVADO.\n";
                    }
                    samples.add(new Sample(artifact, version, variant, title, text.getBytes(StandardCharsets.UTF_8)));
                }
            }
            quota.requireSpace(connection, scope.project(), samples.stream().mapToLong(sample -> sample.bytes().length).sum());
            for (var sample : samples) {
                update(connection, """
                        INSERT INTO pilot_artifact (project_id, id, version, dossier_id, variant, purpose,
                                                    title, author_id, content, sha256)
                        VALUES (?, ?, ?, ?, ?, 'AUDITORIA_V1', ?, ?, ?, ?)
                        """, scope.project(), sample.artifact(), sample.version(), dossier, sample.variant(), sample.title(), actor,
                        sample.bytes(), FixtureCatalog.sha256(sample.bytes()));
            }
            UUID event = audit.record(connection, scope.project(), actor, "PREPARAR_DOSSIE", "PERMITIDO",
                    dossier.toString(), Map.of("fixture", SAMPLE));
            return Map.of("id", dossier, "version", 1, "eventId", event);
        });
    }

    JsonNode approve(PilotAccess.Scope scope, String actor, String key, UUID dossier, Reference reference, int expected) {
        return commands.execute(scope, actor, "APROVAR_ARTEFATO", key, dossier + ":" + reference + ":" + expected,
                reference.toString(), connection -> {
                    var item = first(connection, """
                            SELECT * FROM pilot_artifact WHERE project_id = ? AND dossier_id = ?
                              AND id = ? AND version = ? AND media_type = 'text/plain' FOR UPDATE
                            """, row -> artifact(row, scope), scope.project(), dossier, reference.id(), reference.version());
                    if (item == null) {
                        throw PilotAccess.invisible();
                    }
                    if (actor.equals(item.author())) {
                        throw conflict(403, "AUTOAPROVACAO_NEGADA", "Outra pessoa deve revisar esta versão.");
                    }
                    requireVersion(expected, "APROVADO".equals(item.state()) ? 2 : 1);
                    update(connection, """
                            UPDATE pilot_artifact SET approved_by = ?, approved_at = clock_timestamp()
                            WHERE project_id = ? AND id = ? AND version = ? AND approved_at IS NULL
                            """, actor, scope.project(), reference.id(), reference.version());
                    UUID event = audit.record(connection, scope.project(), actor, "APROVAR_ARTEFATO", "PERMITIDO",
                            reference.toString(), Map.of("sha256", item.sha256(), "author", item.author(), "before", 1, "after", 2));
                    return Map.of("artifact", reference, "state", "APROVADO", "version", 2, "eventId", event);
                });
    }

    JsonNode grant(PilotAccess.Scope scope, String actor, String key, Reference reference, String purpose,
                   String recipient, OffsetDateTime expiresAt) {
        return commands.execute(scope, actor, "CONCEDER_ACESSO", key,
                reference + ":" + purpose + ":" + recipient + ":" + expiresAt.toInstant(), reference.toString(), connection -> {
                    intake.lockProject(connection, scope.project());
                    var item = first(connection, """
                            SELECT * FROM pilot_artifact
                            WHERE project_id = ? AND id = ? AND version = ? AND approved_at IS NOT NULL AND purpose = ?
                            FOR UPDATE
                            """, row -> artifact(row, scope), scope.project(), reference.id(), reference.version(), purpose);
                    String eligible = first(connection, """
                            SELECT i.external_issuer FROM pilot_identity i JOIN pilot_membership m ON i.id = m.identity_id
                            WHERE i.id = ? AND i.organization_id = ? AND m.project_id = ? AND 'VVB' = ANY(m.roles)
                              AND m.valid_from <= statement_timestamp() AND m.valid_until > statement_timestamp()
                            """, row -> row.getString(1), recipient, scope.organization(), scope.project());
                    if (item == null || eligible == null) {
                        throw PilotAccess.invisible();
                    }
                    var obsoleteDocument = first(connection, """
                            SELECT true FROM pilot_document_variant v JOIN pilot_document_dossier d
                             ON (d.project_id,d.id)=(v.project_id,v.dossier_id)
                            WHERE v.project_id=? AND v.artifact_id=? AND v.revision=? AND d.revision>v.revision LIMIT 1
                            """, row -> true, scope.project(), reference.id(), reference.version());
                    if (obsoleteDocument != null && !historicalM1Access(connection, scope.project(), reference, purpose, recipient)) {
                        throw conflict(409, "PACOTE_SUPERADO", "Use a revisão atual para nova concessão.");
                    }
                    var affectedClosure = first(connection, """
                            SELECT true FROM pilot_period_dependent d
                            JOIN pilot_period_closure c USING (project_id,period,revision)
                            WHERE d.project_id=? AND d.kind='PACOTE' AND d.reference=? AND d.reference_version=?
                              AND EXISTS (SELECT 1 FROM pilot_period_state h
                                          WHERE h.project_id=d.project_id AND h.period=d.period AND h.version>c.state_version)
                            LIMIT 1
                            """, row -> true, scope.project(), reference.id().toString(), reference.version());
                    if (affectedClosure != null) {
                        throw conflict(409, "PACOTE_AFETADO_POR_REABERTURA", "Use uma versão revisada para nova concessão.");
                    }
                    var active = first(connection, """
                            SELECT id FROM pilot_grant WHERE project_id = ? AND artifact_id = ? AND artifact_version = ?
                              AND purpose = ? AND recipient_id = ? AND revoked_at IS NULL
                              AND expires_at > statement_timestamp()
                            """, row -> row.getObject("id", UUID.class), scope.project(), reference.id(),
                            reference.version(), purpose, recipient);
                    if (active != null) {
                        throw conflict(409, "CONCESSAO_VIGENTE", "Já há uma concessão vigente para esta versão.");
                    }

                    UUID id = UUID.randomUUID();
                    int affected = update(connection, """
                            INSERT INTO pilot_grant (project_id, id, artifact_id, artifact_version, purpose, recipient_id,
                                                     granted_by, granted_at, expires_at)
                            SELECT ?, ?, ?, ?, ?, ?, ?, statement_timestamp(), ?
                            WHERE CAST(? AS timestamptz) > statement_timestamp()
                            """, scope.project(), id, reference.id(), reference.version(), purpose, recipient, actor,
                            expiresAt, expiresAt);
                    if (affected == 0) {
                        throw PilotProblem.invalid("VIGENCIA_INVALIDA", "expiresAt", "TX-E3-25",
                                "A validade da concessão deve terminar no futuro.");
                    }
                    UUID event = audit.record(connection, scope.project(), actor, "CONCEDER_ACESSO", "PERMITIDO",
                            id.toString(), Map.of("artifact", reference, "purpose", purpose, "recipient", recipient,
                                    "proof", scope.powerProof(), "transition", "TX-E3-25"));
                    return Map.of("grant", grant(connection, scope, id), "eventId", event);
                });
    }

    JsonNode revoke(PilotAccess.Scope scope, String actor, String key, UUID id, int expected) {
        return commands.execute(scope, actor, "REVOGAR_ACESSO", key, id + ":" + expected, id.toString(), connection -> {
            lockGrant(connection, scope, id);
            var current = grant(connection, scope, id);
            requireVersion(expected, current.version());
            if (current.revokedAt() != null) {
                throw conflict(409, "TRANSICAO_INVALIDA", "A concessão já foi revogada.");
            }
            update(connection, """
                    UPDATE pilot_grant SET revoked_by = ?, revoked_at = clock_timestamp()
                    WHERE project_id = ? AND id = ?
                    """, actor, scope.project(), id);
            UUID event = audit.record(connection, scope.project(), actor, "REVOGAR_ACESSO", "PERMITIDO", id.toString(),
                    Map.of("artifact", current.artifact(), "proof", scope.powerProof(), "transition", "TX-E3-47",
                            "before", current.version(), "after", current.version() + 1));
            return Map.of("grant", grant(connection, scope, id), "eventId", event);
        });
    }

    private boolean historicalM1Access(Connection connection, String project, Reference reference, String purpose,
                                       String recipient) throws SQLException {
        if (!"AUDITORIA_V1".equals(purpose)) return false;
        // O VVB contratado para M2 consulta a revisão que recebeu o parecer de M1.
        return first(connection, """
                SELECT true FROM pilot_vvb_audit prior
                JOIN pilot_vvb_audit next ON next.project_id=prior.project_id AND next.kind='M2'
                WHERE prior.project_id=? AND prior.kind='M1' AND prior.state->>'state'='PARECER_POSITIVO'
                    AND prior.vvb<>next.vvb AND next.vvb=?
                    AND prior.state#>>'{pack,reservedArtifact}'=?
                    AND (prior.state#>>'{pack,pack,reference,revision}')::int=?
                """, row -> true, project, recipient, reference.id().toString(), reference.version()) != null;
    }

    JsonNode captureReceipt(PilotAccess.Scope scope, String actor, String key, UUID id, int expected) {
        return commands.execute(scope, actor, "CAPTURAR_RECIBO", key, id + ":" + expected, id.toString(), connection -> {
            lockGrant(connection, scope, id);
            var current = grant(connection, scope, id);
            requireVersion(expected, current.version());
            if (current.receipt() != null || current.revokedAt() != null) {
                throw conflict(409, "TRANSICAO_INVALIDA", "A concessão já tem recibo ou foi revogada.");
            }
            var issuer = first(connection, "SELECT external_issuer FROM pilot_identity WHERE id = ?",
                    row -> row.getString(1), current.recipient());
            int captured = update(connection, """
                    INSERT INTO pilot_receipt
                    SELECT ?, ?, ?, ?, statement_timestamp()
                    WHERE CAST(? AS timestamptz) > statement_timestamp()
                    """, scope.project(), id, issuer, actor, current.expiresAt());
            if (captured == 0) {
                throw conflict(409, "TRANSICAO_INVALIDA", "A concessão está expirada.");
            }
            UUID event = audit.record(connection, scope.project(), actor, "CAPTURAR_RECIBO", "PERMITIDO", id.toString(),
                    Map.of("artifact", current.artifact(), "issuer", issuer, "capturedBy", actor,
                            "evidence", "SIM-RECIBO-VVB:" + id, "before", current.version(), "after", current.version() + 1));
            return Map.of("grant", grant(connection, scope, id), "eventId", event);
        });
    }

    List<Grant> grants(PilotAccess.Scope scope) {
        return database.read(connection -> query(connection,
                GRANTS + " ORDER BY g.granted_at DESC, g.id LIMIT 100", ProtectedArtifacts::grant, scope.project()));
    }

    private Grant grant(Connection connection, PilotAccess.Scope scope, UUID id) throws SQLException {
        return first(connection, GRANTS + " AND g.id = ?", ProtectedArtifacts::grant, scope.project(), id);
    }

    private void lockGrant(Connection connection, PilotAccess.Scope scope, UUID id) throws SQLException {
        if (first(connection, "SELECT id FROM pilot_grant WHERE project_id = ? AND id = ? FOR UPDATE",
                row -> row.getObject(1, UUID.class), scope.project(), id) == null) {
            throw PilotAccess.invisible();
        }
    }

    private static void requireVersion(int expected, int current) {
        if (expected != current) {
            throw conflict(412, "VERSAO_OBSOLETA", "Recarregue o registro antes de confirmar.");
        }
    }

    private static PilotProblem conflict(int status, String code, String message) {
        return new PilotProblem(status, code, "action", "OP-E6-03/08", message);
    }

    private static boolean drafts(PilotAccess.Scope scope) {
        return scope.has(PilotAccess.PREP) || scope.has(PilotAccess.REV);
    }

    private static Artifact artifact(ResultSet row, PilotAccess.Scope scope) throws SQLException {
        boolean external = scope.has(PilotAccess.VVB);
        var approvedBy = row.getString("approved_by");
        return new Artifact(row.getObject("id", UUID.class), row.getInt("version"),
                external ? null : row.getObject("dossier_id", UUID.class), row.getString("variant"),
                row.getString("purpose"), row.getString("title"), external ? null : row.getString("author_id"),
                external ? null : approvedBy, approvedBy == null ? "EM_REVISAO" : "APROVADO", row.getString("sha256"),
                row.getString("media_type"), row.getString("edition"));
    }

    private static Grant grant(ResultSet row) throws SQLException {
        var revokedAt = row.getObject("revoked_at", OffsetDateTime.class);
        var capturedAt = row.getObject("captured_at", OffsetDateTime.class);
        var receipt = capturedAt == null ? null : new Receipt(row.getString("issuer"), row.getString("captured_by"), capturedAt);
        int version = 1 + (revokedAt == null ? 0 : 1) + (receipt == null ? 0 : 1);
        return new Grant(row.getObject("id", UUID.class), version,
                new Reference(row.getObject("artifact_id", UUID.class), row.getInt("artifact_version")),
                row.getString("purpose"), row.getString("recipient_id"), row.getString("granted_by"),
                row.getObject("granted_at", OffsetDateTime.class), row.getObject("expires_at", OffsetDateTime.class),
                revokedAt, receipt);
    }
}
