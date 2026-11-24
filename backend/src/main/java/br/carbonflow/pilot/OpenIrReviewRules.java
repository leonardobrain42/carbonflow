package br.carbonflow.pilot;

import java.util.Set;

final class OpenIrReviewRules {
    private OpenIrReviewRules() {}

    static void validate(String outcome,String annualUpdateBasis,String assessmentState) {
        if(outcome==null || !Set.of("CHECKED_INTERNAL","NEEDS_INFORMATION","REJECTED").contains(outcome))
            throw new IllegalArgumentException("Informe um parecer válido.");
        if(annualUpdateBasis==null || !Set.of("PLAN_ONLY","OBSERVED_UPDATE","INSUFFICIENT").contains(annualUpdateBasis))
            throw new IllegalArgumentException("Classifique a evidência de atualização anual.");
        if("CHECKED_INTERNAL".equals(outcome) &&
            (!"PENDING_REVIEW".equals(assessmentState) || !"OBSERVED_UPDATE".equals(annualUpdateBasis)))
            throw new IllegalArgumentException("Checagem interna requer rota anual específica e atualização observada; plano ou declaração não basta.");
    }
}
