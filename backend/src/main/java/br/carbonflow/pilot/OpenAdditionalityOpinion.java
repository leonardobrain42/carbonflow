package br.carbonflow.pilot;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Additionality is three human opinions. A typed guide and the market test do not conclude it.
 */
final class OpenAdditionalityOpinion {
    private static final Pattern DAY = Pattern.compile("\\d{4}-\\d{2}-\\d{2}");

    /** Independent internal opinion recorded for one criterion; never a conclusion of additionality. */
    record Review(String state, Integer version, String outcome, boolean stale,
                  java.time.OffsetDateTime reviewedAt) {}

    record Criterion(String code, String title, String state, String stateLabel, String text,
                     boolean humanOpinion, boolean concludes, Review review) {}

    record Opinion(String declarationRoute, boolean declarationIsOpinion,
                   boolean marketTestClosesApplicability, boolean concludesAdditionality,
                   boolean usableForE8, List<Criterion> criteria) {}

    private OpenAdditionalityOpinion() {}

    static Opinion assess(OpenApplicability.Readiness readiness, Map<String, String> answers) {
        String route = text(answers, "route");
        boolean marketClosed = readiness != null && readiness.market() != null
            && readiness.market().closesApplicability();
        return new Opinion(route, false, marketClosed, false, false, List.of(
            criterion("POSITIVE_LIST", "Lista positiva",
                "A lista positiva é parecer humano. A rota escrita no guia e a citação da VMD0049 "
                    + "não aprovam a adicionalidade. O teste de mercado da aplicabilidade é outro gate "
                    + "e não entra como parecer."),
            criterion("REGULATORY_SURPLUS", "Excedente regulatório",
                "O excedente regulatório é parecer humano, separado da lista positiva. "
                    + "A declaração não o conclui."),
            criterion("CHRONOLOGY", "Cronologia", chronology(answers))));
    }

    private static Criterion criterion(String code, String title, String text) {
        return new Criterion(code, title, "HUMAN_OPINION", "Parecer humano", text, true, false,
            new Review("PENDING_HUMAN_OPINION", null, null, false, null));
    }

    private static String chronology(Map<String, String> answers) {
        String considered = day(answers, "considered");
        String commitment = day(answers, "commitment");
        if (commitment != null && considered != null && commitment.compareTo(considered) < 0) {
            return "O compromisso declarado é anterior à consideração da receita. "
                + "Isso pede parecer humano e não reprova nem aprova a adicionalidade.";
        }
        if (commitment == null || considered == null) {
            return "A cronologia ainda não tem as duas datas. A ausência não é zero nem reprovação.";
        }
        return "As datas declaradas não são o parecer. A cronologia continua com revisão humana.";
    }

    private static String day(Map<String, String> answers, String key) {
        String value = text(answers, key);
        return value != null && DAY.matcher(value).matches() ? value : null;
    }

    private static String text(Map<String, String> answers, String key) {
        if (answers == null) return null;
        String value = answers.get(key);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
