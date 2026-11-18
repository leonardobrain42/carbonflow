package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class ProjectInvitations {
    record Invitation(UUID id, String projectId, String projectTitle, UUID planId, int planVersion,
                      String recipient, String role, List<String> activities, String state,
                      OffsetDateTime acceptUntil, OffsetDateTime accessUntil) {}
    record OwnerView(boolean canSend, List<Invitation> invitations, List<String> supportedActivities) {}
    private record Entry(Invitation view, UUID sender, UUID authorityRequest, int authorityVersion,
                         UUID grant, UUID grantee, UUID organization) {}

    private static final String SELECT = """
            SELECT i.*, p.organization_id, plan.email_normalized, plan.proposed_role, plan.activities,
                   g.identity_id AS grantee,
                   CASE WHEN i.revoked_at IS NOT NULL OR g.revoked_at IS NOT NULL THEN 'REVOKED'
                        WHEN i.access_until <= statement_timestamp() THEN 'EXPIRED'
                        WHEN i.accepted_at IS NOT NULL THEN 'ACCEPTED'
                        WHEN i.accept_until <= statement_timestamp() THEN 'EXPIRED'
                        ELSE 'SENT' END AS invitation_state
            FROM app_project_invitation i
            JOIN app_project p ON p.id = i.project_id
            JOIN app_project_invitation_plan_revision plan
              ON plan.project_id = i.project_id AND plan.plan_id = i.plan_id AND plan.version = i.plan_version
            LEFT JOIN app_project_collaborator_grant g ON g.id = i.grant_id
            """;
    private static final ZoneId PROJECT_DATES = ZoneId.of("America/Sao_Paulo");

    private final PilotDatabase database;
    private final AccountAccess accounts;
    private final DynamicProjectScope scopes;
    private final SandboxAccountMailbox mailbox;

    ProjectInvitations(PilotDatabase database, AccountAccess accounts, DynamicProjectScope scopes, SandboxAccountMailbox mailbox) {
        this.database = database;
        this.accounts = accounts;
        this.scopes = scopes;
        this.mailbox = mailbox;
    }

    OwnerView ownerView(String token, String project) {
        return scopes.read(token, project, "VIEW_ACTIVE_INVITATIONS", ProjectInvitations::ownerView);
    }

    Invitation send(String token, String project, UUID planId, String version, String key) {
        return scopes.write(token, project, "SEND_PROJECT_INVITATION", (connection, scope) -> {
            var power = sendingPower(connection, project, scope.actor().id());
            if (power == null) throw denied("PODER_ENVIO_NECESSARIO", "O envio requer poder vigente para convidar neste projeto.");
            int expected = PilotCommands.expected(version);
            String command = PilotCommands.key(key);
            Entry replay = first(connection, SELECT + " WHERE i.project_id = ? AND i.sender_id = ? AND i.command_key = ?",
                    ProjectInvitations::entry, project, scope.actor().id(), command);
            if (replay != null) {
                if (!replay.view().planId().equals(planId) || replay.view().planVersion() != expected) {
                    throw conflict("CHAVE_REUSADA", "A chave pertence a outro envio.");
                }
                return replay.view();
            }
            var plan = ProjectInvitationPlans.view(connection, scope).plans().stream()
                    .filter(item -> item.id().equals(planId)).findFirst().orElseThrow(ProjectInvitations::invisible);
            PilotCommands.requireVersion(expected, plan.version());
            var details = plan.details();
            if (details.email().equals(scope.actor().email())
                    || plan.independence() == ProjectInvitationPlans.Independence.SAME_PREPARER_AND_REVIEWER
                    || plan.independence() == ProjectInvitationPlans.Independence.DECLARED_CONFLICT) {
                throw conflict("INDEPENDENCIA_DO_CONVITE", "Revise o destinatário e resolva o conflito de funções antes do envio.");
            }
            var supported = ProjectGuides.supportedActivities(connection);
            if (details.activities().stream().anyMatch(activity -> !supported.contains(activity.name()))) {
                throw conflict("ATIVIDADES_INDISPONIVEIS", "O plano contém uma atividade ainda indisponível para convite.");
            }
            if (Boolean.TRUE.equals(first(connection, """
                    SELECT true FROM app_project_invitation WHERE project_id = ? AND plan_id = ? AND revoked_at IS NULL
                    AND access_until > statement_timestamp() AND (accepted_at IS NOT NULL OR accept_until > statement_timestamp())
                    LIMIT 1
                    """, row -> true, project, planId))) {
                throw conflict("CONVITE_JA_ATIVO", "Já existe um convite ativo para este plano.");
            }
            if (conflictingRole(connection, project, details.email(), details.role().name())) {
                throw conflict("INDEPENDENCIA_DO_CONVITE", "O destinatário já tem convite ou acesso vigente para a função oposta.");
            }
            OffsetDateTime now = now(connection);
            OffsetDateTime accessUntil = power.validUntil().atOffset(java.time.ZoneOffset.UTC);
            if (details.deadline() != null && details.deadline().isBefore(accessUntil.atZoneSameInstant(PROJECT_DATES).toLocalDate())) {
                accessUntil = details.deadline().plusDays(1).atStartOfDay(PROJECT_DATES).toOffsetDateTime();
            }
            if (!accessUntil.isAfter(now)) throw conflict("PRAZO_ENCERRADO", "Atualize o prazo do plano para continuar.");
            OffsetDateTime acceptUntil = now.plusDays(7).isBefore(accessUntil) ? now.plusDays(7) : accessUntil;
            // Stable across a retry if the sandbox notification was written before the transaction committed.
            UUID id = UUID.nameUUIDFromBytes((project + ":" + scope.actor().id() + ":" + command).getBytes(StandardCharsets.UTF_8));
            mailbox.requireAvailable();
            update(connection, """
                    INSERT INTO app_project_invitation(id,project_id,project_title,plan_id,plan_version,sender_id,
                        authority_request_id,authority_version,command_key,sent_at,accept_until,access_until)
                    SELECT ?,id,title,?,?,?,?,?,?,?,?,? FROM app_project WHERE id = ?
                    """, id, planId, plan.version(), scope.actor().id(), power.requestId(), power.decisionVersion(),
                    command, now, acceptUntil, accessUntil, project);
            mailbox.deliver(id, new SandboxAccountMailbox.Message(details.email(), "PROJECT_INVITATION", id.toString(), acceptUntil));
            return find(connection, id).view();
        });
    }

    List<Invitation> received(String token) {
        return database.read(connection -> {
            var actor = accounts.requireIdentity(connection, token);
            return query(connection, SELECT + " WHERE plan.email_normalized = ? ORDER BY i.sent_at DESC, i.id",
                    ProjectInvitations::entry, actor.email()).stream().map(Entry::view).toList();
        });
    }

    Invitation accept(String token, UUID id) {
        return database.transaction(connection -> {
            var actor = accounts.lockIdentity(connection, token);
            Entry current = find(connection, id);
            if (current == null || !current.view().recipient().equals(actor.email())) throw invisible();
            String project = current.view().projectId();
            first(connection, "SELECT id FROM app_project WHERE id = ? FOR UPDATE", row -> row.getString(1), project);
            current = find(connection, id);
            if (current == null || !current.view().recipient().equals(actor.email())) throw invisible();
            if ("ACCEPTED".equals(current.view().state()) && actor.id().equals(current.grantee())) return current.view();
            if (!"SENT".equals(current.view().state())) {
                throw conflict("CONVITE_INDISPONIVEL", "O convite não está disponível para aceite.");
            }
            var power = sendingPower(connection, project, current.sender());
            if (power == null || !power.requestId().equals(current.authorityRequest())
                    || power.decisionVersion() != current.authorityVersion()) {
                throw conflict("CONVITE_INDISPONIVEL", "O convite perdeu a autorização original de envio.");
            }
            if (actor.id().equals(current.sender()) || conflictingRole(connection, project, actor.email(), current.view().role())
                    || ("REV".equals(current.view().role()) && authored(connection, project, actor.id()))) {
                throw conflict("INDEPENDENCIA_DO_CONVITE", "A função é incompatível com a participação deste destinatário no projeto.");
            }

            var accepted = now(connection);
            if (!current.view().acceptUntil().isAfter(accepted)) {
                throw conflict("CONVITE_INDISPONIVEL", "O prazo para aceitar o convite encerrou.");
            }
            UUID grant = UUID.randomUUID();
            var activities = connection.createArrayOf("text", current.view().activities().toArray(String[]::new));
            try {
                update(connection, """
                        INSERT INTO app_project_collaborator_grant(id, project_id, organization_id, environment, identity_id,
                            granted_by, role, activities, valid_from, valid_until)
                        VALUES(?,?,?,'SANDBOX',?,?,?,?,?,?)
                        """, grant, project, current.organization(), actor.id(), current.sender(), current.view().role(),
                        activities, accepted, current.view().accessUntil());
            } finally {
                activities.free();
            }
            update(connection, "UPDATE app_project_invitation SET accepted_at = ?, grant_id = ? WHERE id = ?", accepted, grant, id);
            DynamicProjectScope.audit(connection, actor.id(), project, "ACCEPT_PROJECT_INVITATION", "PERMITTED", "OK");
            return find(connection, id).view();
        });
    }

    OwnerView revoke(String token, String project, UUID id) {
        return scopes.write(token, project, "REVOKE_PROJECT_INVITATION", (connection, scope) -> {
            Entry current = find(connection, id);
            if (current == null || !current.view().projectId().equals(project)) throw invisible();
            if (!"REVOKED".equals(current.view().state())) {
                OffsetDateTime revoked = now(connection);
                update(connection, "UPDATE app_project_invitation SET revoked_at = ?, revoked_by = ? WHERE id = ?",
                        revoked, scope.actor().id(), id);
                if (current.grant() != null) {
                    update(connection, "UPDATE app_project_collaborator_grant SET revoked_at = ?, revoked_by = ? WHERE id = ?",
                            revoked, scope.actor().id(), current.grant());
                }
            }
            return ownerView(connection, scope);
        });
    }

    private static boolean conflictingRole(Connection connection, String project, String email, String role) throws SQLException {
        return Boolean.TRUE.equals(first(connection, SELECT + """
                 WHERE i.project_id = ? AND plan.email_normalized = ? AND plan.proposed_role <> ?
                 AND i.revoked_at IS NULL AND g.revoked_at IS NULL AND i.access_until > statement_timestamp()
                 AND (i.accepted_at IS NOT NULL OR i.accept_until > statement_timestamp()) LIMIT 1
                """, row -> true, project, email, role));
    }

    private static boolean authored(Connection connection, String project, UUID actor) throws SQLException {
        return Boolean.TRUE.equals(first(connection, """
                SELECT EXISTS(SELECT 1 FROM app_project_intake_draft_revision WHERE project_id = ? AND actor_id = ?)
                    OR EXISTS(SELECT 1 FROM app_project_preliminary_dossier WHERE project_id = ? AND actor_id = ?)
                    OR EXISTS(SELECT 1 FROM app_technical_guide_revision WHERE project_id = ? AND actor_id = ?)
                """, row -> row.getBoolean(1), project, actor, project, actor, project, actor));
    }

    private static OwnerView ownerView(Connection connection, DynamicProjectScope.Context scope) throws SQLException {
        var list = query(connection, SELECT + " WHERE i.project_id = ? ORDER BY i.sent_at DESC, i.id",
                ProjectInvitations::entry, scope.id()).stream().map(Entry::view).toList();
        return new OwnerView(sendingPower(connection, scope.id(), scope.actor().id()) != null, list, ProjectGuides.supportedActivities(connection));
    }

    private static ComplianceDecisions.GrantedPower sendingPower(Connection connection, String project, UUID actor) throws SQLException {
        return ComplianceDecisions.active(connection, project, actor).stream()
                .filter(power -> power.power() == ProjectAuthorityRequests.Power.SEND_INVITATIONS).findFirst().orElse(null);
    }

    private static OffsetDateTime now(Connection connection) throws SQLException {
        return first(connection, "SELECT statement_timestamp()", row -> row.getObject(1, OffsetDateTime.class));
    }

    private static Entry find(Connection connection, UUID id) throws SQLException {
        return first(connection, SELECT + " WHERE i.id = ?", ProjectInvitations::entry, id);
    }

    private static Entry entry(ResultSet row) throws SQLException {
        var array = row.getArray("activities");
        try {
            return new Entry(new Invitation(row.getObject("id", UUID.class), row.getString("project_id"), row.getString("project_title"),
                    row.getObject("plan_id", UUID.class), row.getInt("plan_version"), row.getString("email_normalized"),
                    row.getString("proposed_role"), List.of((String[]) array.getArray()), row.getString("invitation_state"),
                    row.getObject("accept_until", OffsetDateTime.class), row.getObject("access_until", OffsetDateTime.class)),
                    row.getObject("sender_id", UUID.class), row.getObject("authority_request_id", UUID.class),
                    row.getInt("authority_version"), row.getObject("grant_id", UUID.class),
                    row.getObject("grantee", UUID.class), row.getObject("organization_id", UUID.class));
        } finally {
            array.free();
        }
    }

    private static PilotProblem conflict(String code, String message) {
        return new PilotProblem(409, code, "invitation", "CF-IMP-013", message);
    }

    private static PilotProblem denied(String code, String message) {
        return new PilotProblem(403, code, "invitation", "CF-IMP-013", message);
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404, "RECURSO_NAO_VISIVEL", "invitation", "CF-IMP-013", "Convite indisponível.");
    }
}
