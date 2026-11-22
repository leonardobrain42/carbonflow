package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.query;

import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectRepository {
    private final PilotDatabase database;

    ProjectRepository(PilotDatabase database) {
        this.database = database;
    }

    List<ProjectContext.Pilot> pilotScopes(PilotAccess.Identity actor, String projectId) {
        return database.read(connection -> List.copyOf(query(connection, """
                SELECT p.id, p.organization_id, p.environment, m.roles, m.power_proof
                FROM pilot_membership m JOIN pilot_project p ON p.id = m.project_id
                WHERE m.identity_id = ? AND p.organization_id = ? AND p.environment = 'SIMULACAO'
                  AND m.valid_from <= clock_timestamp() AND m.valid_until > clock_timestamp()
                  AND (CAST(? AS text) IS NULL OR p.id = ?)
                ORDER BY p.id
                """, row -> new ProjectContext.Pilot(row.getString("id"), row.getString("organization_id"),
                row.getString("environment"), List.of((String[]) row.getArray("roles").getArray()),
                row.getString("power_proof")), actor.id(), actor.organization(), projectId, projectId)));
    }

    List<ProjectContext.Draft> listDrafts(UUID actorId, UUID organizationId) {
        return drafts(actorId, Objects.requireNonNull(organizationId), null);
    }

    List<ProjectContext.Draft> listDrafts(UUID actorId) {
        return drafts(actorId, null, null);
    }

    Optional<ProjectContext.Draft> findDraft(UUID actorId, UUID organizationId, String projectId) {
        Objects.requireNonNull(projectId);
        return drafts(actorId, Objects.requireNonNull(organizationId), projectId).stream().findFirst();
    }

    private List<ProjectContext.Draft> drafts(UUID actorId, UUID organizationId, String projectId) {
        return database.read(connection -> List.copyOf(query(connection, """
                SELECT DISTINCT ON (id) id, organization_id, environment, state, version, access
                FROM (
                    SELECT p.id, p.organization_id, p.environment, p.state, p.version,
                           'OWNER' AS access, 0 AS rank
                    FROM app_project p
                    JOIN app_organization_membership m
                        ON m.organization_id = p.organization_id AND m.identity_id = p.created_by
                    JOIN app_identity i ON i.id = m.identity_id
                    WHERE m.identity_id = ? AND (CAST(? AS uuid) IS NULL OR m.organization_id = ?)
                      AND p.environment = 'SANDBOX'
                      AND i.status = 'ACTIVE' AND m.role = 'DRAFT_OWNER' AND p.state = 'DRAFT'
                      AND m.revoked_at IS NULL AND m.valid_from <= statement_timestamp()
                      AND (m.valid_until IS NULL OR m.valid_until > statement_timestamp())
                      AND (CAST(? AS text) IS NULL OR p.id = ?)
                    UNION ALL
                    SELECT p.id, p.organization_id, p.environment, p.state, p.version,
                           'CALCULATION', 1
                    FROM app_project p
                    JOIN app_project_collaborator_grant g ON g.project_id = p.id
                    JOIN app_identity i ON i.id = g.identity_id
                    WHERE g.identity_id = ? AND (CAST(? AS uuid) IS NULL OR p.organization_id = ?)
                      AND p.environment = 'SANDBOX' AND p.state = 'DRAFT' AND i.status = 'ACTIVE'
                      AND g.revoked_at IS NULL AND g.valid_from <= statement_timestamp()
                      AND g.valid_until > statement_timestamp()
                      AND 'CALCULATION' = ANY (g.activities)
                      AND (CAST(? AS text) IS NULL OR p.id = ?)
                ) listed
                ORDER BY id, rank
                """, row -> new ProjectContext.Draft(row.getString("id"), row.getObject("organization_id", UUID.class),
                row.getString("environment"), row.getString("state"), row.getInt("version"),
                row.getString("access")),
                actorId, organizationId, organizationId, projectId, projectId,
                actorId, organizationId, organizationId, projectId, projectId)));
    }
}
