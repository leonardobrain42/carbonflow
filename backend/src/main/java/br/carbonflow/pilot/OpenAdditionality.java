package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static br.carbonflow.pilot.PilotSql.update;

import java.sql.Connection;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

/** Read model: additionality stays a human opinion even when the guide is filled. */
@ApplicationScoped
class OpenAdditionality {
    private final DynamicProjectScope scope;
    private final OpenApplicability applicability;
    private final ObjectMapper json;

    OpenAdditionality(DynamicProjectScope scope, OpenApplicability applicability, ObjectMapper json) {
        this.scope = scope;
        this.applicability = applicability;
        this.json = json;
    }

    record ReviewInput(String outcome, String rationale, String evidenceReference) {}
    record Result(OpenAdditionalityOpinion.Opinion view, boolean created) {}
    private static final String RULE = "CF-OPEN-010";
    private static final Set<String> CRITERIA = Set.of("POSITIVE_LIST", "REGULATORY_SURPLUS", "CHRONOLOGY");

    OpenAdditionalityOpinion.Opinion get(String token, String project) {
        return scope.read(token, project, "VIEW_OPEN_APPLICABILITY", (connection, context) ->
            view(connection, context.id()));
    }

    private OpenAdditionalityOpinion.Opinion view(Connection connection, String project) throws SQLException {
        var readiness = applicability.readinessOf(connection, project);
        String raw = first(connection, """
            SELECT answers::text FROM app_technical_guide_revision
            WHERE project_id=? AND guide_id='additionality'
            ORDER BY version DESC LIMIT 1
            """, r -> r.getString(1), project);
        Map<String, String> answers;
        try {
            answers = raw == null ? Map.of() : json.readValue(raw, new TypeReference<>() {});
        } catch (com.fasterxml.jackson.core.JacksonException ex) {
            throw new IllegalStateException("Respostas do guia ilegíveis.");
        }
        var opinion = OpenAdditionalityOpinion.assess(readiness, answers);
        var criteria = new ArrayList<OpenAdditionalityOpinion.Criterion>();
        for (var criterion : opinion.criteria()) {
            var review = review(connection, project, criterion.code());
            criteria.add(new OpenAdditionalityOpinion.Criterion(criterion.code(), criterion.title(), criterion.state(),
                criterion.stateLabel(), criterion.text(), criterion.humanOpinion(), criterion.concludes(), review));
        }
        return new OpenAdditionalityOpinion.Opinion(opinion.declarationRoute(), opinion.declarationIsOpinion(),
            opinion.marketTestClosesApplicability(), opinion.concludesAdditionality(), opinion.usableForE8(),
            List.copyOf(criteria));
    }

    /** Latest opinion of a criterion; it goes stale when the declaration it saw changes. */
    private static OpenAdditionalityOpinion.Review review(Connection c, String project, String criterion)
            throws SQLException {
        var latest = first(c, """
            SELECT version,outcome,encode(context_sha256,'hex'),created_at FROM app_open_additionality_opinion
            WHERE project_id=? AND criterion=? ORDER BY version DESC LIMIT 1
            """, r -> new Object[]{r.getInt(1), r.getString(2), r.getString(3), r.getObject(4, OffsetDateTime.class)},
            project, criterion);
        if (latest == null) return new OpenAdditionalityOpinion.Review("PENDING_HUMAN_OPINION", null, null, false, null);
        boolean stale = !latest[2].equals(contextSha(c, project, criterion));
        String state = stale ? "STALE_NOT_ADMITTED" : latest[1] + "_NOT_ADMITTED";
        return new OpenAdditionalityOpinion.Review(state, (Integer) latest[0], (String) latest[1], stale,
            (OffsetDateTime) latest[3]);
    }

    /** What the reviewer saw: the current OPEN declaration and additionality guide revisions. */
    private static String contextSha(Connection c, String project, String criterion) throws SQLException {
        Integer declaration = first(c, "SELECT max(version) FROM app_project_open_applicability_revision WHERE project_id=?",
            r -> r.getObject(1, Integer.class), project);
        Integer guide = first(c, "SELECT max(version) FROM app_technical_guide_revision WHERE project_id=? AND guide_id='additionality'",
            r -> r.getObject(1, Integer.class), project);
        return java.util.HexFormat.of().formatHex(AccessSecrets.digest(
            "OPEN-ADDITIONALITY-CONTEXT@1|" + criterion + "|" + declaration + "|" + guide));
    }

    Result review(String cookie, String project, String criterion, String match, String key, ReviewInput input) {
        return scope.write(cookie, project, "REVIEW_OPEN_ADDITIONALITY", (c, ctx) -> {
            if (ctx.owner() || !"REV".equals(ctx.role()) || !CRITERIA.contains(criterion)) throw hidden();
            if (input == null || input.outcome() == null || !Set.of("CHECKED_INTERNAL", "RETURNED").contains(input.outcome()))
                throw invalid("outcome", "Informe CHECKED_INTERNAL ou RETURNED.");
            String rationale = clean(input.rationale(), 30, 2000, "rationale");
            String evidence = clean(input.evidenceReference(), 15, 600, "evidenceReference");
            String command = PilotCommands.key(key);
            int expected = PilotCommands.expected(match);
            byte[] fingerprint = AccessSecrets.digest("OPEN-ADDITIONALITY-REVIEW|" + project + "|" + criterion + "|"
                + expected + "|" + input.outcome() + "|" + rationale + "|" + evidence);
            Object[] previous = first(c, """
                SELECT project_id,criterion,fingerprint FROM app_open_additionality_command
                WHERE actor_id=? AND command_key=?
                """, r -> new Object[]{r.getString(1), r.getString(2), r.getBytes(3)}, ctx.actor().id(), command);
            if (previous != null) {
                if (!project.equals(previous[0]) || !criterion.equals(previous[1])
                        || !java.security.MessageDigest.isEqual(fingerprint, (byte[]) previous[2]))
                    throw new PilotProblem(409, "ADDITIONALITY_COMMAND_CONFLICT", "Idempotency-Key", RULE,
                        "Chave de idempotência já utilizada.");
                return new Result(view(c, project), false);
            }
            // Independence: the reviewer cannot be the author of what is being reviewed.
            UUID guideAuthor = first(c, """
                SELECT actor_id FROM app_technical_guide_revision WHERE project_id=? AND guide_id='additionality'
                ORDER BY version DESC LIMIT 1
                """, r -> r.getObject(1, UUID.class), project);
            UUID declarationAuthor = first(c, """
                SELECT author_id FROM app_project_open_applicability_revision WHERE project_id=?
                ORDER BY version DESC LIMIT 1
                """, r -> r.getObject(1, UUID.class), project);
            if (guideAuthor == null && declarationAuthor == null)
                throw new PilotProblem(409, "ADDITIONALITY_DECLARATION_MISSING", "criterion", RULE,
                    "Registre a declaração de aplicabilidade e o guia de adicionalidade antes do parecer.");
            if (ctx.actor().id().equals(guideAuthor) || ctx.actor().id().equals(declarationAuthor))
                throw new PilotProblem(403, "ADDITIONALITY_REVIEW_NOT_INDEPENDENT", "reviewer", RULE,
                    "O revisor precisa ser independente de quem registrou a declaração e o guia.");
            Integer latest = first(c, """
                SELECT max(version) FROM app_open_additionality_opinion WHERE project_id=? AND criterion=?
                """, r -> r.getObject(1, Integer.class), project, criterion);
            int current = latest == null ? 0 : latest;
            PilotCommands.requireVersion(expected, current);
            if (current >= 100000000) throw invalid("version", "Limite de pareceres.");
            update(c, """
                INSERT INTO app_open_additionality_opinion
                (project_id,organization_id,environment,criterion,version,outcome,rationale,evidence_reference,
                 context_sha256,reviewer_id)
                VALUES (?,?,'SANDBOX',?,?,?,?,?,?,?)
                """, project, ctx.organization(), criterion, current + 1, input.outcome(), rationale, evidence,
                java.util.HexFormat.of().parseHex(contextSha(c, project, criterion)),
                ctx.actor().id());
            update(c, """
                INSERT INTO app_open_additionality_command(actor_id,command_key,project_id,criterion,fingerprint,opinion_version)
                VALUES (?,?,?,?,?,?)
                """, ctx.actor().id(), command, project, criterion, fingerprint, current + 1);
            return new Result(view(c, project), true);
        });
    }

    private static String clean(String value, int min, int max, String field) {
        String text = value == null ? "" : value.strip();
        if (text.length() < min || text.length() > max || text.codePoints().anyMatch(ch -> Character.isISOControl(ch) && ch != '\n'))
            throw invalid(field, "Informe entre " + min + " e " + max + " caracteres.");
        return text;
    }

    private static PilotProblem invalid(String field, String detail) {
        return PilotProblem.invalid("ADDITIONALITY_REVIEW_INVALID", field, RULE, detail);
    }

    private static PilotProblem hidden() {
        return new PilotProblem(404, "ADDITIONALITY_REVIEW_UNAVAILABLE", "project", RULE, "Recurso indisponível.");
    }
}
