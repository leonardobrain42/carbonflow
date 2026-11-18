package br.carbonflow.pilot;

import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.enterprise.context.ApplicationScoped;

@ApplicationScoped
class SectionAssessment {
    enum Data { MISSING, RECORDED }
    enum Proof { UNLINKED, PENDING, REJECTED, ACCEPTED_INTERNAL, RECHECK_REQUIRED }
    record Link(UUID candidateId, UUID evidenceId, int guideVersion, String sha256, String locator,
                GuideFacts.Status decision, Integer factVersion, Integer currentFactVersion) {}
    record Result(String code, String title, String field, RequirementGraph.Classification incidence, Data data,
                  Proof proof, RequirementGraph.Status check, boolean humanReviewRequired, String reason, String ruleSha256,
                  Link evidence) {}
    record Payload(String section, int guideRevision, RequirementResolver.Act act, LocalDate dateUsed,
                   RequirementResolver.State catalogState, String catalogReason, String packId, Integer packEdition,
                   String inputSha256, String calculation, String externalAct, List<Result> results) {}
    record Report(String sha256, Payload assessment) {}

    private final DynamicProjectScope scopes;
    private final SectionReadiness readiness;
    private final ObjectMapper json;

    SectionAssessment(DynamicProjectScope scopes, SectionReadiness readiness, ObjectMapper json) {
        this.scopes = scopes;
        this.readiness = readiness;
        this.json = json;
    }

    Report get(String cookie, String project, String section, RequirementResolver.Act act) {
        return scopes.read(cookie,project,"VIEW_GUIDES",(connection,scope) -> {
            var evaluated = readiness.evaluate(connection,scope,section,act);
            var view = evaluated.view();
            var guide = evaluated.guide();
            var links = view.requirements().isEmpty() ? Map.<String,Link>of()
                    : GuideFacts.assessmentLinks(connection,scope.id(),section,guide.version());
            var results = view.requirements().stream().map(node -> {
                var rule = evaluated.sectionRules().get(node.code());
                Link link = links.get(node.field());
                Data data = "referenceActDate".equals(node.field())
                        ? (act == RequirementResolver.Act.PD && view.dateUsed() != null ? Data.RECORDED : Data.MISSING)
                        : (guide.answers().containsKey(node.field()) ? Data.RECORDED : Data.MISSING);
                return new Result(node.code(),node.title(),node.field(),node.classification(),data,
                        proof(link,guide.version()),node.status(),node.interpretation(),
                        node.reason(),sha256(new RequirementResolver.Rule(rule.code(),rule.title(),rule.section(),
                                rule.field(),rule.source(),rule.locator(),rule.condition(),node.dependencies())),link);
            }).toList();
            String input = view.requirements().isEmpty() ? null : sha256(new TreeMap<>(guide.answers()));
            var payload = new Payload(section,guide.version(),act,view.dateUsed(),view.catalogState(),view.catalogReason(),
                    view.packId(),view.packEdition(),input,"PENDING_ACCOUNT_CALCULATION","NOT_EVALUATED",results);
            return new Report(sha256(payload),payload);
        });
    }

    static Proof proof(Link link, int version) {
        if (link == null) return Proof.UNLINKED;
        if (link.guideVersion() != version) return Proof.RECHECK_REQUIRED;
        return switch (link.decision()) {
            case PENDING -> Proof.PENDING;
            case REJECTED -> Proof.REJECTED;
            case ACCEPTED -> java.util.Objects.equals(link.factVersion(),link.currentFactVersion())
                    ? Proof.ACCEPTED_INTERNAL : Proof.RECHECK_REQUIRED;
        };
    }

    private String sha256(Object value) {
        return FixtureCatalog.sha256(json.valueToTree(value).toString().getBytes(StandardCharsets.UTF_8));
    }
}
