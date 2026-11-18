package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ComplianceQueue {
    record Case(String projectId, String projectTitle, UUID organizationId, String organizationName, UUID requestId,
                ProjectAuthorityRequests.Power power, String basis, String reference, OffsetDateTime submittedAt) {}
    record Queue(List<Case> cases, Integer nextOffset) {}

    private final PilotDatabase database;
    private final ComplianceStaff staff;
    private final ComplianceEvidence evidence;

    ComplianceQueue(PilotDatabase database, ComplianceStaff staff, ComplianceEvidence evidence) {
        this.database = database;
        this.staff = staff;
        this.evidence = evidence;
    }

    Queue queue(String token, int offset) {
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            if (offset < 0) throw new PilotProblem(400, "PEDIDO_INVALIDO", "offset", "CF-IMP-028", "Página inválida.");
            var organizations = connection.createArrayOf("uuid", actor.organizations().toArray(UUID[]::new));
            try {
                var rows = query(connection, """
                        SELECT p.id, p.title, o.id AS organization_id, o.display_name, r.request_id,
                               r.requested_power, r.representation_basis, r.evidence_reference, r.recorded_at
                        FROM app_project_authority_request_revision r
                        JOIN app_project p ON p.id = r.project_id
                        JOIN app_organization o ON o.id = p.organization_id
                        WHERE r.version = 1 AND p.environment = 'SANDBOX' AND o.id = ANY(?)
                          AND p.created_by <> ? AND r.actor_id <> ?
                          AND NOT EXISTS (SELECT 1 FROM app_project_authority_request_revision newer
                            WHERE newer.project_id = r.project_id AND newer.request_id = r.request_id AND newer.version > 1)
                        ORDER BY r.recorded_at, p.id, r.request_id LIMIT 41 OFFSET ?
                        """, row -> new Case(row.getString("id"), row.getString("title"),
                        row.getObject("organization_id", UUID.class), row.getString("display_name"),
                        row.getObject("request_id", UUID.class), ProjectAuthorityRequests.Power.valueOf(row.getString("requested_power")),
                        row.getString("representation_basis"), row.getString("evidence_reference"),
                        row.getObject("recorded_at", OffsetDateTime.class)), organizations, actor.id(), actor.id(), offset);
                ComplianceStaff.audit(connection, actor.id(), null, "VIEW_QUEUE");
                return rows.size() > 40 ? new Queue(List.copyOf(rows.subList(0, 40)), offset + 40) : new Queue(rows, null);
            } finally {
                organizations.free();
            }
        });
    }

    List<ComplianceEvidence.Document> documents(String token, String project, UUID request) {
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            requireCase(connection, actor, project, request, false);
            ComplianceEvidence.requireDocumentAccess(connection, project, request);
            return ComplianceEvidence.documents(connection, project, request);
        });
    }

    byte[] download(String token, String project, UUID request, UUID document) {
        return database.transaction(connection -> {
            var actor = staff.require(connection, token);
            requireCase(connection, actor, project, request, false);
            byte[] bytes = evidence.download(connection, project, request, document);
            ComplianceStaff.audit(connection, actor.id(), project, "DOWNLOAD_DOCUMENT");
            return bytes;
        });
    }

    static void requireCase(Connection connection, ComplianceStaff.Staff staff, String project, UUID request,
                            boolean write) throws SQLException {
        UUID organization = first(connection, """
                SELECT p.organization_id FROM app_project p JOIN app_project_authority_request_revision r
                  ON r.project_id = p.id AND r.request_id = ? AND r.version = 1
                WHERE p.id = ? AND p.environment = 'SANDBOX' AND p.created_by <> ? AND r.actor_id <> ?
                """ + (write ? "FOR UPDATE OF p" : "FOR SHARE OF p"), row -> row.getObject(1, UUID.class),
                request, project, staff.id(), staff.id());
        if (organization == null || !staff.organizations().contains(organization)) throw ComplianceStaff.unavailable();
        ComplianceEvidence.requireOpen(connection, project, request);
    }
}
