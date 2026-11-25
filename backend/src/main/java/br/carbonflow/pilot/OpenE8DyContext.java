package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/**
 * Structured Dy resolution for one project year (D14, #96/#97). The executor reads the exact versioned assessment,
 * its independent internal review and its current rights, never the legacy scalar pin. Never normative admission.
 */
record OpenE8DyContext(UUID assessmentId,Integer version,String assessmentSha256,String state,String contextSha256,
                       UUID author,UUID reviewer,BigDecimal erc,BigDecimal erf,boolean absence) {
    static final String BASIS="STRUCTURED_PROJECT_YEAR";
    static final String CHECKED="CHECKED_INTERNAL_NOT_ADMITTED";
    /** D14 Q05.2: year 1 only with the proved absence of fleet credits in the exploratory window. */
    static final String FIRST_YEAR_ABSENCE="FIRST_YEAR_ABSENCE_CHECKED_NOT_ADMITTED";
    /** Q05.3: sharing by EVs in circulation stays blocked until the national EVR denominator is admitted (G03). */
    static final String ALLOCATION_BLOCKED="ALLOCATION_BLOCKED";

    /** Facts are computed by the caller; this keeps the decision a pure, testable function. */
    static String classify(Integer projectYear,boolean exists,boolean stale,boolean conclusive,boolean arithmetic,
                           boolean absence,boolean itemized,boolean reviewedExact,boolean sharedAllocation) {
        if (projectYear==null) return "TIMING_UNRESOLVED";
        if (!exists) return projectYear==1?"FIRST_YEAR_REVIEW_REQUIRED":"MISSING_FOR_PROJECT_YEAR";
        if (stale) return "STALE";
        if (sharedAllocation) return ALLOCATION_BLOCKED;
        // A positive finding needs itemized issuances reconciled to the declared totals; absence is proved by the matrix.
        if (!conclusive || !arithmetic || !reviewedExact || (!absence && !itemized)) return "REVIEW_REQUIRED";
        if (projectYear==1) return absence?FIRST_YEAR_ABSENCE:"FIRST_YEAR_REVIEW_REQUIRED";
        return CHECKED;
    }

    static OpenE8DyContext of(String project,Integer year,UUID id,Integer version,String sha,String state,
                              String reviewSha,UUID author,UUID reviewer,BigDecimal erc,BigDecimal erf,boolean absence) {
        return new OpenE8DyContext(id,version,sha,state,OpenIrAssessments.digest("OPEN-E8-DY-CONTEXT@1",
            project,year,id,version,sha,state,reviewSha,author,reviewer),author,reviewer,erc,erf,absence);
    }

    static OpenE8DyContext none(String project,Integer year,String state) {
        return of(project,year,null,null,null,state,null,null,null,null,null,false);
    }

    boolean usable() { return assessmentId!=null && (CHECKED.equals(state)||FIRST_YEAR_ABSENCE.equals(state)); }

    OpenGridCalculationMath.Discount discount() {
        return usable()?new OpenGridCalculationMath.Discount(erc,erf,absence):null;
    }
    boolean authoredBy(UUID actor) {
        return actor!=null&&(Objects.equals(actor,author)||Objects.equals(actor,reviewer));
    }
    OpenE8ProjectRuns.InputReference reference() {
        return new OpenE8ProjectRuns.InputReference("DY",BASIS,assessmentId,version,assessmentSha256,state);
    }
}
