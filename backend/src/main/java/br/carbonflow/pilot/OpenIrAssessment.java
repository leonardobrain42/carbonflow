package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.EnumMap;
import java.util.HexFormat;
import java.util.List;
import java.util.UUID;

/**
 * Preparatory decision kernel for CF-OPEN-007, VM0038 1.1 sections 9.1 and 10.1.
 * Only the annually updated Brazilian OPEN LDV/DCFC/SIN route is supported.
 * References and actor identities must be resolved and authorized by the future
 * persistence/application layer; this pure kernel does not certify evidence.
 */
final class OpenIrAssessment {
    static final String POLICY = "OPEN-IR-ASSESSMENT@1";
    static final String BRANCH = "OPEN_LDV_DCFC_SIN_BR";
    static final String RULE_REFERENCE = "VM0038/1.1/10.1";

    enum Frequency { ANNUAL, NON_ANNUAL, UNKNOWN }
    enum Basis { PROJECT_SPECIFIC, DEFAULT_VALUES, UNKNOWN }
    enum Parameter { EVR, AFEC, MPG, EF_FUEL }
    enum Status {
        OUT_OF_SCOPE, PENDING_METHOD, PENDING_EVIDENCE,
        PENDING_REVIEW, REVIEW_STALE, REVIEW_CONFLICT, REVIEW_REJECTED,
        REVIEW_RECORDED_NOT_ADMITTED
    }

    /** A reference to an immutable source version, not proof of its admissibility. */
    record Evidence(Parameter parameter, UUID id, int version, String sha256, int sourceYear) {}
    record Input(String project, String branch, int projectYear, int sourceYear,
                 Frequency frequency, Basis basis, UUID author, List<Evidence> evidence) {
        Input {
            evidence = evidence == null ? List.of() : List.copyOf(evidence);
        }
    }

    /** The application layer must authenticate reviewer, competence and grants. */
    record Review(UUID reviewer, String assessmentSha256, boolean approved, String rationale) {}

    record Result(String policy, String ruleReference, Status status, BigDecimal candidate,
                  String assessmentSha256, List<String> reasons, boolean usableForE8) {
        Result {
            reasons = List.copyOf(reasons);
            if (usableForE8) throw new IllegalArgumentException("IR not admitted");
        }
    }

    private OpenIrAssessment() {}

    static Result assess(Input input, Review review) {
        if (input == null || !BRANCH.equals(input.branch()))
            return result(Status.OUT_OF_SCOPE, null, null, List.of("BRANCH_UNSUPPORTED"));
        if (input.project() == null || input.project().isBlank() || input.author() == null
                || input.projectYear() < 1 || input.sourceYear() < 1900
                || input.sourceYear() > 2100)
            return result(Status.PENDING_METHOD, null, null, List.of("CONTEXT_INCOMPLETE"));
        if (input.frequency() != Frequency.ANNUAL || input.basis() != Basis.PROJECT_SPECIFIC)
            return result(Status.PENDING_METHOD, null, null, List.of("ANNUAL_PROJECT_SPECIFIC_ROUTE_REQUIRED"));

        var references = new EnumMap<Parameter, Evidence>(Parameter.class);
        var reasons = new ArrayList<String>();
        for (Evidence evidence : input.evidence()) {
            if (evidence.parameter() == null || evidence.id() == null || evidence.version() < 1
                    || evidence.sha256() == null || !evidence.sha256().matches("[0-9a-f]{64}")) {
                reasons.add("INVALID_EVIDENCE_REFERENCE");
                continue;
            }
            if (references.putIfAbsent(evidence.parameter(), evidence) != null)
                reasons.add("DUPLICATE_" + evidence.parameter());
            if (evidence.sourceYear() != input.sourceYear())
                reasons.add("SOURCE_YEAR_MISMATCH_" + evidence.parameter());
        }
        for (Parameter parameter : Parameter.values())
            if (!references.containsKey(parameter)) reasons.add("MISSING_" + parameter);
        if (!reasons.isEmpty()) return result(Status.PENDING_EVIDENCE, null, null, reasons);

        // Fixed enum ordering; length-prefix project prevents ambiguous concatenation.
        var material = new StringBuilder(POLICY).append('|').append(RULE_REFERENCE)
            .append('|').append(input.project().length()).append(':').append(input.project())
            .append('|').append(input.branch()).append('|').append(input.projectYear())
            .append('|').append(input.sourceYear()).append('|').append(input.frequency())
            .append('|').append(input.basis()).append('|').append(input.author());
        for (var entry : references.entrySet()) {
            var e = entry.getValue();
            material.append('|').append(entry.getKey()).append('|').append(e.id())
                .append('|').append(e.version()).append('|').append(e.sha256())
                .append('|').append(e.sourceYear());
        }
        String sha = sha256(material.toString());
        if (review == null) return result(Status.PENDING_REVIEW, BigDecimal.ONE, sha, List.of("REVIEW_REQUIRED"));
        if (!sha.equals(review.assessmentSha256()))
            return result(Status.REVIEW_STALE, BigDecimal.ONE, sha, List.of("ASSESSMENT_CHANGED"));
        if (review.reviewer() == null || input.author().equals(review.reviewer()))
            return result(Status.REVIEW_CONFLICT, BigDecimal.ONE, sha, List.of("DISTINCT_REVIEWER_REQUIRED"));
        if (review.rationale() == null || review.rationale().isBlank())
            return result(Status.PENDING_REVIEW, BigDecimal.ONE, sha, List.of("REVIEW_RATIONALE_REQUIRED"));
        if (!review.approved())
            return result(Status.REVIEW_REJECTED, BigDecimal.ONE, sha, List.of("REVIEW_REJECTED"));
        return result(Status.REVIEW_RECORDED_NOT_ADMITTED, BigDecimal.ONE, sha,
            List.of("EVIDENCE_ADMISSION_AND_AUTHORIZATION_REQUIRED"));
    }

    private static Result result(Status status, BigDecimal value, String sha, List<String> reasons) {
        return new Result(POLICY, RULE_REFERENCE, status, value, sha, reasons, false);
    }

    private static String sha256(String material) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                .digest(material.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }
}
