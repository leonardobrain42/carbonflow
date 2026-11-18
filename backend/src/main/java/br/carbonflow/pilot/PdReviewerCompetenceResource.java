package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.UUID;
import jakarta.ws.rs.*;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

@Path("/v1/compliance")
@Produces(MediaType.APPLICATION_JSON)
public class PdReviewerCompetenceResource {
    record Decision(String reference, OffsetDateTime validUntil, boolean revoke) {}
    record Entry(int version, String reference, OffsetDateTime validUntil, boolean active) {}
    private final PilotDatabase database;
    private final ComplianceStaff staff;
    PdReviewerCompetenceResource(PilotDatabase database, ComplianceStaff staff) {
        this.database = database;
        this.staff = staff;
    }

    private static final String ENTRY = "/projects/{project}/reviewers/{reviewer}/sections/{section}/competence";
    record Target(String projectId, String projectTitle, UUID reviewerId, String reviewerName, String sectionId, String sectionTitle) {}

    @GET @Path("/pd-reviewers")
    public Response targets(@CookieParam(AccountAccess.COOKIE) String token) {
        return database.transaction(c -> {
            var officer = staff.require(c, token);
            var result = new java.util.ArrayList<Target>();
            for (UUID org : officer.organizations()) {
                var options = PilotSql.query(c, """
                        SELECT DISTINCT p.id, p.title, g.identity_id, i.display_name, t.id, t.title
                        FROM app_project p JOIN app_project_collaborator_grant g ON g.project_id=p.id
                        JOIN app_identity i ON i.id=g.identity_id
                        JOIN app_pd_section_version s ON s.project_id=p.id JOIN app_technical_guide t ON t.id=s.section_id
                        WHERE p.organization_id=? AND p.created_by<>? AND p.environment='SANDBOX' AND p.state='DRAFT'
                        AND g.role='REV' AND g.identity_id<>? AND g.revoked_at IS NULL
                        AND g.valid_from<=statement_timestamp() AND g.valid_until>statement_timestamp()
                        AND t.activity=ANY(g.activities) ORDER BY p.title,i.display_name,t.title
                        """, r -> new Target(r.getString(1),r.getString(2),r.getObject(3,UUID.class),
                                r.getString(4),r.getString(5),r.getString(6)), org,officer.id(),officer.id());
                for (var option : options) {
                    if (ProjectCollaboration.actions(c,option.projectId(),officer.id()).isEmpty()
                            && ProjectCollaboration.actions(c,option.projectId(),option.reviewerId()).contains("DECIDE_PD_REVIEW")) {
                        result.add(option);
                    }
                }
            }
            return Response.ok(result).header("Cache-Control","private, no-store").build();
        });
    }

    @GET @Path(ENTRY)
    public Response current(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                            @PathParam("reviewer") UUID reviewer, @PathParam("section") String section) {
        return database.transaction(c -> {
            authorize(c, token, project, reviewer, section);
            return response(latest(c, project, reviewer, section));
        });
    }

    @PUT @Path(ENTRY) @Consumes(MediaType.APPLICATION_JSON)
    public Response record(@CookieParam(AccountAccess.COOKIE) String token, @PathParam("project") String project,
                           @PathParam("reviewer") UUID reviewer, @PathParam("section") String section,
                           @HeaderParam("If-Match") String match, Decision input) {
        return database.transaction(c -> {
            UUID verifier = authorize(c, token, project, reviewer, section);
            Entry current = latest(c, project, reviewer, section);
            PilotCommands.requireVersion(PilotCommands.expected(match), current.version());
            OffsetDateTime now = first(c, "SELECT statement_timestamp()", r -> r.getObject(1, OffsetDateTime.class));
            if (input == null || input.reference() == null || input.reference().isBlank() || input.reference().length() > 1000
                    || (input.revoke() ? !current.active() : input.validUntil() == null || !input.validUntil().isAfter(now))) {
                throw new PilotProblem(400, "COMPETENCIA_INVALIDA", "reference", "CF-IMP-024",
                        "Informe a referência da decisão e uma vigência futura para a concessão.");
            }
            update(c, """
                    INSERT INTO app_pd_reviewer_competence(project_id,section_id,reviewer_id,verified_by,reference,valid_until,version)
                    VALUES(?,?,?,?,?,?,?)
                    """, project, section, reviewer, verifier, input.reference().strip(),
                    input.revoke() ? now : input.validUntil(), current.version()+1);
            return response(latest(c, project, reviewer, section));
        });
    }

    private UUID authorize(Connection c, String token, String project, UUID reviewer, String section) throws SQLException {
        var officer = staff.require(c, token);
        UUID organization = first(c, """
                SELECT organization_id FROM app_project WHERE id=? AND state='DRAFT' AND environment='SANDBOX'
                AND created_by<>? FOR UPDATE
                """, r -> r.getObject(1, UUID.class), project, officer.id());
        if (organization == null || !officer.organizations().contains(organization)
                || officer.id().equals(reviewer) || !ProjectCollaboration.actions(c, project, officer.id()).isEmpty()) {
            throw ComplianceStaff.unavailable();
        }
        String activity = first(c, "SELECT activity FROM app_technical_guide WHERE id=?", r -> r.getString(1), section);
        var grants = ProjectCollaboration.actions(c, project, reviewer);
        if (activity == null || !grants.contains("DECIDE_PD_REVIEW") || !grants.contains("GUIDE_" + activity)) {
            throw ComplianceStaff.unavailable();
        }
        return officer.id();
    }

    private static Entry latest(Connection c, String project, UUID reviewer, String section) throws SQLException {
        Entry result = first(c, """
                SELECT version,reference,valid_until,valid_until>statement_timestamp() FROM app_pd_reviewer_competence
                WHERE project_id=? AND reviewer_id=? AND section_id=? ORDER BY version DESC LIMIT 1
                """, r -> new Entry(r.getInt(1), r.getString(2), r.getObject(3, OffsetDateTime.class), r.getBoolean(4)),
                project, reviewer, section);
        return result == null ? new Entry(0, null, null, false) : result;
    }

    private static Response response(Entry entry) {
        return Response.ok(entry).header("Cache-Control", "private, no-store").tag(Integer.toString(entry.version())).build();
    }
}
