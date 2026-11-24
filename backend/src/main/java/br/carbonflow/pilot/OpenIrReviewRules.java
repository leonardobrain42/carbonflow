package br.carbonflow.pilot;

import java.util.Set;

final class OpenIrReviewRules {
    private OpenIrReviewRules() {}

    static void validate(String outcome,String annualUpdateBasis,String assessmentState) {
        validate(outcome,annualUpdateBasis,assessmentState,2);
    }

    static void validate(String outcome,String annualUpdateBasis,String assessmentState,int year) {
        if(outcome==null || !Set.of("CHECKED_INTERNAL","NEEDS_INFORMATION","REJECTED","INITIAL_BASELINE_CHECKED").contains(outcome))
            throw new IllegalArgumentException("Informe um parecer válido.");
        if(annualUpdateBasis==null || !Set.of("PLAN_ONLY","OBSERVED_UPDATE","INSUFFICIENT").contains(annualUpdateBasis))
            throw new IllegalArgumentException("Classifique a evidência de atualização anual.");
        if("INITIAL_BASELINE_CHECKED".equals(outcome) &&
            (year!=1 || !"PENDING_REVIEW".equals(assessmentState) || !"PLAN_ONLY".equals(annualUpdateBasis)))
            throw new IllegalArgumentException("Parecer inicial exige ano 1, rota anual específica e plano, sem afirmar atualização observada.");
        if("CHECKED_INTERNAL".equals(outcome) &&
            (year<=1 || !"PENDING_REVIEW".equals(assessmentState) || !"OBSERVED_UPDATE".equals(annualUpdateBasis)))
            throw new IllegalArgumentException("Checagem interna requer rota anual específica e atualização observada; plano ou declaração não basta.");
    }
}
