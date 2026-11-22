package br.carbonflow.pilot;

import java.util.Map;

/**
 * The baseline section names the preparatory fleet reference.
 * The guide text is not the PBEV rating, and the PD stays unready.
 */
final class OpenPdBaselineNote {
    record Note(String period, String situation, Integer observationYear, String cycle,
                String declaredRating, boolean declarationIsRating, boolean admitsBaseline,
                boolean copiesLicensedRatings, boolean usableForE8, boolean marksDocumentReady,
                String text) {}

    private OpenPdBaselineNote() {}

    static Note from(OpenE8FleetSelections.View selection, Map<String, String> answers) {
        String declared = text(answers, "rating");
        if (selection == null) {
            return new Note("M1", "ABSENT", null, null, declared, false, false, false, false, false,
                "Nenhuma seleção de frota está nomeada neste documento. "
                    + "O texto do guia não substitui o par PBEV. Ausência não é consumo zero. "
                    + "O PD não fica pronto.");
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
                    + "O texto do guia não substitui o par PBEV. O PD não fica pronto.");
    }

    private static String text(Map<String, String> answers, String key) {
        if (answers == null) return null;
        String value = answers.get(key);
        if (value == null) return null;
        String trimmed = value.trim();
        return trimmed.isEmpty() ? null : trimmed;
    }
}
