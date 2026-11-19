package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import jakarta.enterprise.context.ApplicationScoped;
import org.eclipse.microprofile.config.inject.ConfigProperty;

/**
 * Platform-only acquisition workflow. Work revisions do not publish sources, grant content
 * rights, approve technical parameters or change a customer's project.
 */
@ApplicationScoped
class PlatformSourceWork {
    record Work(String needCode, String title, String kind, String stage, String blockedCapability,
                String priority, String status, UUID ownerId, String ownerName, int version,
                OffsetDateTime createdAt, OffsetDateTime updatedAt) {}
    record Board(boolean canManage, List<Work> work) {}
    record Assignee(UUID id, String name) {}
    record Event(int version, String status, UUID ownerId, String ownerName,
                 UUID actorId, String actorName, String note, OffsetDateTime recordedAt) {}
    record Detail(Work work, List<Event> history) {}
    record NewRequest(String note, UUID ownerId) {}
    record Change(String status, UUID ownerId, String note) {}
    private record Need(String code, String kind, String catalogState) {}

    private final PilotDatabase database;
    private final PlatformSourceReadiness readiness;
    private final Set<UUID> managers;

    PlatformSourceWork(PilotDatabase database, PlatformSourceReadiness readiness,
            @ConfigProperty(name = "carbonflow.platform.source-work.managers") Optional<String> roster) {
        this.database = database;
        this.readiness = readiness;
        try {
            this.managers = roster.map(s -> Arrays.stream(s.split(",", -1)).map(String::strip)
                    .filter(s1 -> !s1.isEmpty()).map(UUID::fromString)
                    .collect(Collectors.toUnmodifiableSet())).orElse(Set.of());
        } catch (IllegalArgumentException e) {
            throw new IllegalStateException("Cadastro da equipe gestora de fontes inválido.");
        }
    }

    Board board(String token, String method, String edition, String scenario) {
        return database.transaction(connection -> {
            var actor = readiness.requireViewer(connection, token);
            ensureContext(connection, method, edition, scenario);
            var works = query(connection, """
                SELECT w.need_code,n.title,n.kind,n.stage,n.blocked_capability,n.priority,
                       w.status,w.owner_id,coalesce(i.display_name,''),w.version,w.created_at,w.updated_at
                FROM app_platform_source_work w
                JOIN app_methodology_source_need n ON n.code = w.need_code
                LEFT JOIN app_identity i ON i.id = w.owner_id
                WHERE n.methodology = ? AND n.methodology_edition = ? AND n.scenario = ?
                ORDER BY CASE n.priority WHEN 'P0' THEN 0 WHEN 'P1' THEN 1 ELSE 2 END,
                         w.updated_at DESC,w.need_code
                """, PlatformSourceWork::workRow, method, edition, scenario);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_WORK");
            return new Board(managers.contains(actor.id()), List.copyOf(works));
        });
    }

    List<Assignee> assignees(String token) {
        return database.transaction(connection -> {
            manager(connection, token);
            var ids = connection.createArrayOf("uuid", managers.toArray(UUID[]::new));
            try {
                return query(connection, """
                    SELECT id,display_name FROM app_identity
                    WHERE id = ANY(?) AND status = 'ACTIVE' AND email_verified_at IS NOT NULL
                    ORDER BY display_name,id
                    """, r -> new Assignee(r.getObject(1, UUID.class),r.getString(2)), ids);
            } finally {
                ids.free();
            }
        });
    }

    Detail detail(String token, String code) {
        return database.transaction(connection -> {
            var actor = readiness.requireViewer(connection, token);
            var result = detailRow(connection, code);
            ComplianceStaff.audit(connection, actor.id(), null, "VIEW_PLATFORM_SOURCE_WORK_HISTORY");
            return result;
        });
    }

    Detail request(String token, String code, NewRequest input) {
        return database.transaction(connection -> {
            var actor = manager(connection, token);
            Need need = requireNeed(connection, code);
            if ("PROJECT_EVIDENCE".equals(need.kind())) {
                throw bad("needCode", "Evidência de projeto deve ser solicitada ao PP/operador no projeto.");
            }
            if (!"ACTIVE".equals(need.catalogState())) {
                throw bad("needCode", "Contexto metodológico suspenso: não abrir novas solicitações.");
            }
            String note = note(input == null ? null : input.note());
            UUID owner = input == null ? null : input.ownerId();
            validateOwner(connection, owner);
            int inserted = update(connection, """
                INSERT INTO app_platform_source_work(need_code,status,owner_id,created_by)
                VALUES (?,'REQUESTED',?,?)
                ON CONFLICT (need_code) DO NOTHING
                """, code, owner, actor.id());
            if (inserted == 1) {
                update(connection, """
                    INSERT INTO app_platform_source_work_revision(need_code,version,status,owner_id,actor_id,note)
                    VALUES (?,1,'REQUESTED',?,?,?)
                    """,code,owner,actor.id(),note);
                ComplianceStaff.audit(connection,actor.id(),null,"CREATE_PLATFORM_SOURCE_WORK");
            }
            // PUT is idempotent for the need code; the first accepted note remains immutable.
            return detailRow(connection,code);
        });
    }

    Detail revise(String token, String code, int expectedVersion, Change change) {
        return database.transaction(connection -> {
            var actor = manager(connection, token);
            if (change == null || !Set.of("REQUESTED","SOURCING","WAITING_RIGHTS",
                    "READY_FOR_HANDOFF","ON_HOLD").contains(change.status())) {
                throw bad("status","Situação de solicitação inválida.");
            }
            String reason = note(change.note());
            validateOwner(connection,change.ownerId());
            var old = detailRow(connection,code).work();
            if (expectedVersion < 1 || old.version() != expectedVersion) {
                throw new PilotProblem(409,"VERSAO_DIVERGENTE","If-Match","CF-OPEN-002",
                        "A solicitação foi atualizada por outra pessoa. Consulte a versão mais recente.");
            }
            if (!allowed(old.status(),change.status())) {
                throw bad("status","Transição não permitida para esta solicitação.");
            }
            Integer next = first(connection, """
                UPDATE app_platform_source_work SET status = ?,owner_id = ?,
                       version = version + 1,updated_at = statement_timestamp()
                WHERE need_code = ? AND version = ?
                RETURNING version
                """,r -> r.getInt(1),change.status(),change.ownerId(),code,expectedVersion);
            if (next == null) {
                throw new PilotProblem(409,"VERSAO_DIVERGENTE","If-Match","CF-OPEN-002",
                        "Versão alterada durante a atualização.");
            }
            update(connection, """
                INSERT INTO app_platform_source_work_revision
                    (need_code,version,status,owner_id,actor_id,note)
                VALUES (?,?,?,?,?,?)
                """,code,next,change.status(),change.ownerId(),actor.id(),reason);
            ComplianceStaff.audit(connection,actor.id(),null,"UPDATE_PLATFORM_SOURCE_WORK");
            return detailRow(connection,code);
        });
    }

    static boolean allowed(String current, String next) {
        if (current.equals(next)) return true; // owner transfer or progress note
        return switch (current) {
            case "REQUESTED" -> Set.of("SOURCING","WAITING_RIGHTS","ON_HOLD").contains(next);
            case "SOURCING" -> Set.of("WAITING_RIGHTS","READY_FOR_HANDOFF","ON_HOLD").contains(next);
            case "WAITING_RIGHTS" -> Set.of("SOURCING","ON_HOLD").contains(next);
            case "READY_FOR_HANDOFF" -> Set.of("SOURCING","WAITING_RIGHTS","ON_HOLD").contains(next);
            case "ON_HOLD" -> Set.of("SOURCING","WAITING_RIGHTS").contains(next);
            default -> false;
        };
    }

    private ComplianceStaff.Staff manager(Connection connection, String token) throws SQLException {
        var actor = readiness.requireViewer(connection,token);
        if (!managers.contains(actor.id())) {
            throw new PilotProblem(404,"RECURSO_NAO_VISIVEL","manager","CF-OPEN-002",
                    "A gestão de fontes exige permissão específica.");
        }
        return actor;
    }

    private void validateOwner(Connection connection, UUID owner) throws SQLException {
        if (owner == null) return;
        if (!managers.contains(owner) || first(connection, """
                SELECT 1 FROM app_identity WHERE id = ? AND status = 'ACTIVE'
                  AND email_verified_at IS NOT NULL
                """,r -> r.getInt(1),owner) == null) {
            throw bad("ownerId","O responsável precisa integrar a equipe gestora autorizada.");
        }
    }

    private static Need requireNeed(Connection connection, String code) throws SQLException {
        if (code == null || !code.matches("[A-Z][A-Z0-9_-]{2,80}")) throw invisible();
        var need = first(connection, """
            SELECT n.code,n.kind,c.catalog_state
            FROM app_methodology_source_need n
            JOIN app_methodology_source_context c
              ON c.methodology = n.methodology AND c.methodology_edition = n.methodology_edition
             AND c.scenario = n.scenario
            WHERE n.code = ?
            """,r -> new Need(r.getString(1),r.getString(2),r.getString(3)),code);
        if (need == null) throw invisible();
        return need;
    }

    private static void ensureContext(Connection connection, String method, String edition,
                                      String scenario) throws SQLException {
        if (method == null || edition == null || scenario == null || method.length() > 40 ||
                edition.length() > 40 || scenario.length() > 120 ||
                first(connection, """
                    SELECT 1 FROM app_methodology_source_context
                    WHERE methodology = ? AND methodology_edition = ? AND scenario = ?
                    """,r -> r.getInt(1),method,edition,scenario) == null) throw invisible();
    }

    private static Detail detailRow(Connection connection, String code) throws SQLException {
        var work = first(connection, """
            SELECT w.need_code,n.title,n.kind,n.stage,n.blocked_capability,n.priority,
                   w.status,w.owner_id,coalesce(i.display_name,''),w.version,w.created_at,w.updated_at
            FROM app_platform_source_work w
            JOIN app_methodology_source_need n ON n.code = w.need_code
            LEFT JOIN app_identity i ON i.id = w.owner_id
            WHERE w.need_code = ?
            """,PlatformSourceWork::workRow,code);
        if (work == null) throw invisible();
        var history = query(connection, """
            SELECT r.version,r.status,r.owner_id,coalesce(o.display_name,''),
                   r.actor_id,coalesce(a.display_name,''),r.note,r.recorded_at
            FROM app_platform_source_work_revision r
            LEFT JOIN app_identity o ON o.id = r.owner_id
            JOIN app_identity a ON a.id = r.actor_id
            WHERE r.need_code = ? ORDER BY r.version DESC
            """,r -> new Event(r.getInt(1),r.getString(2),r.getObject(3,UUID.class),
                   r.getString(4),r.getObject(5,UUID.class),r.getString(6),
                   r.getString(7),r.getObject(8,OffsetDateTime.class)),code);
        return new Detail(work,List.copyOf(history));
    }

    private static Work workRow(java.sql.ResultSet r) throws SQLException {
        return new Work(r.getString(1),r.getString(2),r.getString(3),r.getString(4),r.getString(5),
                        r.getString(6),r.getString(7),r.getObject(8,UUID.class),r.getString(9),
                        r.getInt(10),r.getObject(11,OffsetDateTime.class),
                        r.getObject(12,OffsetDateTime.class));
    }

    private static String note(String value) {
        if (value == null || value.strip().isEmpty() || value.strip().length() > 2000) {
            throw bad("note","Descreva a justificativa (1 a 2.000 caracteres).");
        }
        return value.strip();
    }

    private static PilotProblem bad(String field, String message) {
        return PilotProblem.invalid("SOLICITACAO_INVALIDA",field,"CF-OPEN-002",message);
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","needCode","CF-OPEN-002",
                "Solicitação não disponível para esta equipe.");
    }
}
