package br.carbonflow.pilot;

import java.time.LocalDate;
import java.util.EnumSet;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * Internal, conservative rule deciding whether one structured IR assessment may serve another period
 * of the same project year. It never admits a method, a parameter or a credit and never invents a value:
 * every doubt keeps the period blocked. The policy is an internal engineering choice, not a Verra rule.
 */
final class OpenIrIntraYearReuse {
    static final String POLICY="OPEN-IR-INTRA-YEAR-REUSE@1";

    /** Canonical references behind one period: fleet and rating snapshots, fossil-fuel candidate and cycle. */
    record Canonical(UUID fleetSnapshot,UUID ratingSnapshot,UUID fuelCandidate,String cycle) {
        boolean complete() {
            return fleetSnapshot!=null && ratingSnapshot!=null && fuelCandidate!=null && cycle!=null && !cycle.isBlank();
        }
    }
    /** Civil days in America/Sao_Paulo; {@code until} is exclusive, like the temporal evidence window. */
    record Span(LocalDate from,LocalDate until) {
        boolean valid() { return from!=null && until!=null && from.isBefore(until); }
    }
    record Subject(int projectYear,int sourceYear,Canonical canonical,Span period) {}
    enum Outcome {
        /** Same year pair, equivalent canonical references and evidence covering the period. */
        REUSABLE,
        /** Different relative year/source year, or references that are missing or not equivalent. */
        REFERENCE_MISMATCH,
        /** The period leaves the frozen annual window or declared validity does not cover it. */
        EVIDENCE_NOT_COVERING
    }

    private OpenIrIntraYearReuse() {}

    static Outcome decide(Subject assessed,Subject target,OpenIrAnnualTemporalEvidence.Window frozenYear,
                          List<OpenIrAnnualTemporalEvidence.Item> evidence) {
        if(assessed==null || target==null || assessed.projectYear()<2 ||
           assessed.projectYear()!=target.projectYear() || assessed.sourceYear()!=target.sourceYear() ||
           assessed.canonical()==null || target.canonical()==null ||
           !assessed.canonical().complete() || !assessed.canonical().equals(target.canonical()))
            return Outcome.REFERENCE_MISMATCH;
        if(frozenYear==null || frozenYear.yearFrom()==null || frozenYear.yearUntil()==null ||
           !frozenYear.yearFrom().isBefore(frozenYear.yearUntil()) || target.period()==null || !target.period().valid() ||
           target.period().from().isBefore(frozenYear.yearFrom()) || target.period().until().isAfter(frozenYear.yearUntil()))
            return Outcome.EVIDENCE_NOT_COVERING;
        if(evidence==null) return Outcome.EVIDENCE_NOT_COVERING;
        var seen=EnumSet.noneOf(OpenIrAnnualTemporalEvidence.Metric.class);
        for(var item:evidence) {
            var e=item==null?null:item.evidence();
            if(e==null || e.metric()==null || e.validFrom()==null || e.validUntil()==null || !seen.add(e.metric()))
                return Outcome.EVIDENCE_NOT_COVERING;
            if(e.validFrom().isAfter(target.period().from()) || e.validUntil().isBefore(target.period().until()))
                return Outcome.EVIDENCE_NOT_COVERING;
        }
        return seen.size()==OpenIrAnnualTemporalEvidence.Metric.values().length
            ? Outcome.REUSABLE : Outcome.EVIDENCE_NOT_COVERING;
    }

    /** Distinguishes a reused context from an exact one in the run hash, preserving historical exact hashes. */
    static String marker(String exactReviewSha,String assessedPeriod,Span target,UUID assessment,String assessmentSha) {
        return OpenIrAssessments.digest(POLICY,exactReviewSha,assessedPeriod,
            Objects.requireNonNull(target).from(),target.until(),assessment,assessmentSha);
    }
}
