package br.carbonflow.pilot;

import java.util.Map;

/**
 * The baseline section names the preparatory fleet reference.
 * The guide text is not the PBEV rating, and the PD stays unready.
 */
final class OpenPdBaselineNote {
    /** BL-EX-03: exact EX ANTE reference by version. Metadata only; never stock, ratings or a percentage. */
    record ExAnte(int version, int observationYear, String cycle, String selectionSha256,
                  String state, boolean stale, boolean usableForE8, String text) {}
    record Note(String period, String situation, Integer observationYear, String cycle,
                String declaredRating, boolean declarationIsRating, boolean admitsBaseline,
                boolean copiesLicensedRatings, boolean usableForE8, boolean marksDocumentReady,
                String text, ExAnte exAnte) {}

    private OpenPdBaselineNote() {}

    static Note from(OpenE8FleetSelections.View selection, Map<String, String> answers) {
        return from(selection, null, answers);
    }

    /** {@code exAnte} is the newest EX ANTE selection the viewer may read, or null (absent or not readable). */
    static Note from(OpenE8FleetSelections.View selection, OpenBaselineFleetSelections.View exAnte,
                     Map<String, String> answers) {
        var reference = exAnte(exAnte);
        String declared = text(answers, "rating");
        if (selection == null) {
            return new Note("M1", "ABSENT", null, null, declared, false, false, false, false, false,
                "Nenhuma seleção de frota está nomeada neste documento. "
                    + "O texto do guia não substitui o par PBEV. Ausência não é consumo zero. "
                    + "O PD não fica pronto.", reference);
        }
        boolean current = "PREPARATORY_NOT_ADMITTED".equals(selection.state())
            && !selection.stale() && !selection.usableForE8();
        return new Note(selection.period(), current ? "REFERENCE" : "BLOCKED",
            selection.observationYear(), selection.cycle(), declared, false, false, false, false, false,
            current
                ? "A seleção de frota deste período é uma referência preparatória, presa ao "
                    + "identificador de modelo já gravado. O texto do guia não substitui o par PBEV. "
                    + "A cobertura nacional continua sem total oficial verificado. O PD não fica pronto."
                : "A seleção de frota não está corrente para este documento. "
                    + "O texto do guia não substitui o par PBEV. O PD não fica pronto.", reference);
    }

    private static ExAnte exAnte(OpenBaselineFleetSelections.View v) {
        if (v == null) return null;
        boolean current = !v.stale() && !v.usableForE8() && "PREPARATORY_NOT_ADMITTED".equals(v.state());
        return new ExAnte(v.version(), v.observationYear(), v.cycle(), v.selectionSha256(),
            current ? "PREPARATORY_NOT_ADMITTED" : "STALE_NOT_ADMITTED", !current, false,
            current
                ? "Referência de frota ex ante, versão " + v.version() + ", ano de observação "
                    + v.observationYear() + ". É preparatória e exata por versão: não é admitida, não depende de "
                    + "registro de monitoramento e não alimenta o E8. Esta seção não copia estoque nem rating. "
                    + "A cobertura nacional continua sem total oficial verificado. O PD não fica pronto."
                : "A referência de frota ex ante (versão " + v.version() + ") não está corrente: fonte, "
                    + "classificação, direitos ou início de creditação mudaram. Reconfirme antes de citá-la. "
                    + "O PD não fica pronto.");
    }

    private static String text(Map<String, String> answers, String key) {
        if (answers == null) return null;
        String value = answers.get(key);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
