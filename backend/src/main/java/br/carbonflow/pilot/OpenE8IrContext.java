package br.carbonflow.pilot;

import java.math.BigDecimal;
import java.util.Objects;
import java.util.UUID;

/** Structured IR resolution for one period. Never normative admission. */
record OpenE8IrContext(UUID assessmentId,Integer version,String assessmentSha256,
                       String state,String contextSha256,UUID author,UUID reviewer) {
    static final String BASIS="STRUCTURED_PROJECT_SOURCE_YEAR";
    static final String CHECKED="CHECKED_INTERNAL_NOT_ADMITTED";
    /** Q04 option B: project year 1 only as an ex ante estimate, annual-update route, plan reviewed and linked. */
    static final String FIRST_YEAR_EX_ANTE="FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED";

    static String classify(Integer projectYear,Integer sourceYear,boolean timingResolved,
                           boolean exists,boolean stale,boolean samePeriodReferences,
                           boolean checked,boolean annualProofCurrent,boolean candidateOne,
                           boolean firstYearPlanLinked) {
        if(!timingResolved || projectYear==null || sourceYear==null) return "TIMING_UNRESOLVED";
        if(projectYear==1)
            return exists && !stale && samePeriodReferences && candidateOne && firstYearPlanLinked
                ? FIRST_YEAR_EX_ANTE : "FIRST_YEAR_REVIEW_REQUIRED";
        if(!exists) return "MISSING_FOR_PROJECT_YEAR";
        if(stale) return "STALE";
        if(!samePeriodReferences) return "PERIOD_REFERENCE_MISMATCH";
        if(!checked || !candidateOne) return "REVIEW_REQUIRED";
        if(!annualProofCurrent) return "ANNUAL_EVIDENCE_RECHECK_REQUIRED";
        return CHECKED;
    }

    static OpenE8IrContext of(String project,String period,Integer year,Integer source,
                             UUID id,Integer version,String sha,String state,
                             String reviewSha,String proofSha,UUID author,UUID reviewer) {
        return new OpenE8IrContext(id,version,sha,state,OpenIrAssessments.digest(
            "OPEN-E8-IR-CONTEXT@1",project,period,year,source,id,version,sha,state,reviewSha,proofSha,
            author,reviewer),author,reviewer);
    }

    BigDecimal rehearsalValue() {
        return (CHECKED.equals(state)||FIRST_YEAR_EX_ANTE.equals(state))&&assessmentId!=null?BigDecimal.ONE:null;
    }
    boolean authoredBy(UUID actor) {
        return actor!=null&&(Objects.equals(actor,author)||Objects.equals(actor,reviewer));
    }
    OpenE8ProjectRuns.InputReference reference() {
        return new OpenE8ProjectRuns.InputReference("IR",BASIS,assessmentId,version,assessmentSha256,state);
    }
}
