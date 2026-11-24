package br.carbonflow.pilot;

/** Human technical reconciliation, not automatic source equivalence. */
final class OpenIrHandoffRules {
    private OpenIrHandoffRules() {}

    static String note(String raw) {
        String text=OpenDyAssessmentRules.text(raw,15,2000);
        int separator=text.indexOf(':');
        if(separator<0 || !java.util.Set.of("MATCH","JUSTIFIED_DEVIATION").contains(text.substring(0,separator)))
            throw new IllegalArgumentException("Classifique cada parâmetro como correspondente ou divergência justificada.");
        OpenDyAssessmentRules.text(text.substring(separator+1),15,1950);
        return text;
    }
}
