package br.carbonflow.pilot;

import java.util.List;
import java.util.Map;

/**
 * The additionality section names the three human opinions.
 * The guide route is not copied in as a conclusion, and the PD stays unready.
 */
final class OpenPdAdditionalityNote {
    record Note(String declarationRoute, boolean declarationIsOpinion,
                boolean marketTestClosesApplicability, boolean concludesAdditionality,
                boolean usableForE8, boolean marksDocumentReady, String text,
                List<OpenAdditionalityOpinion.Criterion> criteria) {}

    private OpenPdAdditionalityNote() {}

    static Note from(OpenApplicability.Readiness readiness, Map<String, String> answers) {
        var opinion = OpenAdditionalityOpinion.assess(readiness, answers);
        return new Note(opinion.declarationRoute(), false,
            opinion.marketTestClosesApplicability(), false, false, false,
            "Os três pareceres continuam humanos. A rota escrita no guia não entra no documento "
                + "como conclusão. O PD não fica pronto.",
            opinion.criteria());
    }
}
