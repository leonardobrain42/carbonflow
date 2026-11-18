package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.query;
import static br.carbonflow.pilot.PilotSql.update;

@ApplicationScoped
class GuideFacts {
    enum Status { PENDING, ACCEPTED, REJECTED }
    record Proposal(int guideVersion, String field, UUID requestId) {}
    record Decision(Status outcome, String reason) {}
    record Evidence(UUID id, int guideVersion, String sha256, String sensitivity, String usageScope,
                    UUID author, OffsetDateTime savedAt, String locator) {}
    record Review(Status outcome, String reason, UUID reviewer, OffsetDateTime at) {}
    record Candidate(UUID id, String field, String value, UUID proposedBy, Evidence evidence, Review review,
                     Integer factVersion, int currentVersion, boolean divergent, boolean canReview, UUID requestId, Integer requestVersion) {}
    record View(List<Candidate> candidates, boolean canPropose) {}
    private record GuideSource(String bytes, String value) {}
    private record Row(UUID id, String field, String value, UUID proposer, UUID request, Integer requestVersion, Evidence evidence,
                       Review review, Integer factVersion) {}
    private record Current(int number, String value) {}

    private static final String CANDIDATES = """
            SELECT f.id, f.field, g.answers->>f.field AS value, f.proposed_by, f.request_id, f.request_version, e.id AS evidence_id,
                e.guide_version, e.sha256, e.sensitivity, e.usage_scope, g.actor_id AS source_author, g.saved_at,
                d.outcome, d.reason, d.reviewed_by, d.reviewed_at, v.version AS fact_version
            FROM app_fact_candidate f
            JOIN app_evidence_object e ON e.id=f.evidence_id
            JOIN app_technical_guide_revision g ON g.project_id=e.project_id AND g.guide_id=e.section AND g.version=e.guide_version
            LEFT JOIN app_fact_candidate_decision d ON d.candidate_id=f.id
            LEFT JOIN app_project_fact_version v ON v.candidate_id=f.id
            WHERE f.project_id=? AND f.section=?
            """;

    private final DynamicProjectScope scopes;
    private final ProjectGuides guides;
    private final AcquisitionRequests acquisition;

    GuideFacts(DynamicProjectScope scopes, ProjectGuides guides, AcquisitionRequests acquisition) {
        this.scopes = scopes;
        this.guides = guides;
        this.acquisition = acquisition;
    }

    View view(String token, String project, String section) {
        return scopes.read(token,project,"VIEW_GUIDES",(connection,scope) -> {
            var guide = visibleGuide(connection,scope,section);
            var rows = query(connection,CANDIDATES+" ORDER BY f.proposed_at DESC, f.id",GuideFacts::row,project,section);
            var result = new ArrayList<Candidate>();
            for (Row row : rows) result.add(candidate(connection,scope,section,row));
            return new View(List.copyOf(result),guide.editable());
        });
    }

    Candidate propose(String token, String project, String section, Proposal proposal) {
        return scopes.write(token,project,"SAVE_GUIDE",(connection,scope) -> {
            var guide = visibleGuide(connection,scope,section);
            if (proposal == null || proposal.guideVersion() < 1
                    || guide.fields().stream().noneMatch(field -> field.key().equals(proposal.field()))) {
                throw invalid("field","Selecione um campo e uma versão salva do guia.");
            }
            var source = first(connection, """
                    SELECT answers::text,answers->>?::text FROM app_technical_guide_revision
                    WHERE project_id=? AND guide_id=? AND version=?
                    """, row -> new GuideSource(row.getString(1),row.getString(2)),proposal.field(),project,section,proposal.guideVersion());
            if (source == null) throw invisible();
            if (source.value() == null || source.value().isBlank()) throw invalid("field","O campo está vazio na versão escolhida.");
            Integer requestVersion = requestVersion(connection,scope,section,proposal);

            String sha = FixtureCatalog.sha256(source.bytes().getBytes(StandardCharsets.UTF_8));
            update(connection, """
                    INSERT INTO app_evidence_object(id,project_id,section,guide_version,sha256,registered_by)
                    VALUES (?,?,?,?,?,?) ON CONFLICT (project_id,section,guide_version) DO NOTHING
                    """,UUID.randomUUID(),project,section,proposal.guideVersion(),sha,scope.actor().id());
            UUID evidence = first(connection, """
                    SELECT id FROM app_evidence_object WHERE project_id=? AND section=? AND guide_version=? AND sha256=?
                    """,row -> row.getObject(1,UUID.class),project,section,proposal.guideVersion(),sha);
            if (evidence == null) throw invalid("guideVersion","A origem diverge da versão registrada. Confira a revisão.");

            update(connection, """
                    INSERT INTO app_fact_candidate(id,project_id,section,field,evidence_id,request_id,request_version,proposed_by)
                    VALUES (?,?,?,?,?,?,?,?) ON CONFLICT (project_id,section,field,evidence_id) DO NOTHING
                    """,UUID.randomUUID(),project,section,proposal.field(),evidence,proposal.requestId(),requestVersion,scope.actor().id());
            Row saved = first(connection,CANDIDATES+" AND f.field=? AND f.evidence_id=?",GuideFacts::row,
                    project,section,proposal.field(),evidence);
            if (!java.util.Objects.equals(saved.request(),proposal.requestId())) {
                throw new PilotProblem(409,"FACT_CANDIDATE_EXISTS","requestId","ECA-005",
                        "Já existe uma proposta para este campo e esta origem.");
            }
            return candidate(connection,scope,section,saved);
        });
    }

    Candidate decide(String token, String project, String section, UUID id, String etag, Decision decision) {
        int expected = PilotCommands.expected(etag);
        return scopes.write(token,project,"VIEW_GUIDES",(connection,scope) -> {
            visibleGuide(connection,scope,section);
            Row candidate = find(connection,project,section,id);
            if (!independent(scope,candidate)) throw invisible();
            if (decision == null || decision.outcome() == null || decision.outcome() == Status.PENDING
                    || decision.reason() == null || decision.reason().isBlank() || decision.reason().length()>800) {
                throw invalid("decision","Informe o parecer e o motivo da decisão.");
            }
            String reason = decision.reason().strip();
            if (candidate.review() != null) {
                var review = candidate.review();
                if (review.reviewer().equals(scope.actor().id()) && review.outcome() == decision.outcome() && review.reason().equals(reason)) {
                    return candidate(connection,scope,section,candidate);
                }
                throw new PilotProblem(409,"FACT_ALREADY_REVIEWED","candidate","ECA-005","A proposta já recebeu um parecer.");
            }
            var previous = current(connection,project,section,candidate.field());
            int version = previous == null ? 0 : previous.number();
            PilotCommands.requireVersion(expected,version);
            update(connection, """
                    INSERT INTO app_fact_candidate_decision(candidate_id,outcome,reason,reviewed_by) VALUES (?,?,?,?)
                    """,id,decision.outcome().name(),reason,scope.actor().id());
            if (decision.outcome() == Status.ACCEPTED) {
                update(connection, """
                        INSERT INTO app_project_fact_version(project_id,section,field,version,candidate_id) VALUES (?,?,?,?,?)
                        """,project,section,candidate.field(),version+1,id);
            }
            return candidate(connection,scope,section,find(connection,project,section,id));
        });
    }

    private Integer requestVersion(Connection connection, DynamicProjectScope.Context scope, String section,
                                   Proposal proposal) throws SQLException {
        if (proposal.requestId() == null) return null;
        var request = AcquisitionRequests.require(connection,scope,proposal.requestId());
        var revision = acquisition.latest(connection,request.id());
        if (!section.equals(request.section()) || !proposal.field().equals(request.field())
                || !AcquisitionRequests.visible(scope,request,revision)) throw invisible();
        return revision.number();
    }

    private Candidate candidate(Connection connection, DynamicProjectScope.Context scope, String section, Row row) throws SQLException {
        var current = current(connection,scope.id(),section,row.field());
        UUID request = row.request();
        if (request != null) {
            var linked = AcquisitionRequests.require(connection,scope,request);
            if (!AcquisitionRequests.visible(scope,linked,acquisition.latest(connection,request))) request = null;
        }
        return new Candidate(row.id(),row.field(),row.value(),row.proposer(),row.evidence(),row.review(),row.factVersion(),
                current == null ? 0 : current.number(),
                row.review() == null && current != null && !current.value().equals(row.value()),
                row.review() == null && independent(scope,row),request,request == null ? null : row.requestVersion());
    }

    private ProjectGuides.Guide visibleGuide(Connection connection, DynamicProjectScope.Context scope, String section) throws SQLException {
        return guides.catalog(connection,scope).guides().stream().filter(guide -> guide.id().equals(section))
                .findFirst().orElseThrow(GuideFacts::invisible);
    }

    private static boolean independent(DynamicProjectScope.Context scope, Row candidate) {
        return "REV".equals(scope.role()) && !scope.actor().id().equals(candidate.proposer())
                && !scope.actor().id().equals(candidate.evidence().author());
    }

    private static Current current(Connection connection, String project, String section, String field) throws SQLException {
        return first(connection, """
                SELECT v.version,g.answers->>v.field FROM app_project_fact_version v
                JOIN app_fact_candidate f ON f.id=v.candidate_id
                JOIN app_evidence_object e ON e.id=f.evidence_id
                JOIN app_technical_guide_revision g ON g.project_id=e.project_id AND g.guide_id=e.section AND g.version=e.guide_version
                WHERE v.project_id=? AND v.section=? AND v.field=? ORDER BY v.version DESC LIMIT 1
                """,row -> new Current(row.getInt(1),row.getString(2)),project,section,field);
    }

    private static Row find(Connection connection,String project,String section,UUID id) throws SQLException {
        Row result = first(connection,CANDIDATES+" AND f.id=?",GuideFacts::row,project,section,id);
        if (result == null) throw invisible();
        return result;
    }

    private static Row row(java.sql.ResultSet row) throws SQLException {
        String field = row.getString("field");
        var evidence = new Evidence(row.getObject("evidence_id",UUID.class),row.getInt("guide_version"),row.getString("sha256"),
                row.getString("sensitivity"),row.getString("usage_scope"),row.getObject("source_author",UUID.class),
                row.getObject("saved_at",OffsetDateTime.class),"/answers/"+field);
        String reviewState = row.getString("outcome");
        Review review = reviewState == null ? null : new Review(Status.valueOf(reviewState),row.getString("reason"),
                row.getObject("reviewed_by",UUID.class),row.getObject("reviewed_at",OffsetDateTime.class));
        return new Row(row.getObject("id",UUID.class),field,row.getString("value"),row.getObject("proposed_by",UUID.class),
                row.getObject("request_id",UUID.class),row.getObject("request_version",Integer.class),evidence,review,row.getObject("fact_version",Integer.class));
    }

    private static PilotProblem invisible() {
        return new PilotProblem(404,"RECURSO_NAO_VISIVEL","candidate","ECA-005","Origem ou proposta indisponível para esta conta.");
    }

    private static PilotProblem invalid(String field, String message) {
        return new PilotProblem(422,"FACT_INPUT_INVALID",field,"ECA-005",message);
    }
}
