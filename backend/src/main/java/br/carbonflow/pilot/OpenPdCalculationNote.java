package br.carbonflow.pilot;

import java.util.Comparator;
import java.util.List;

/**
 * The quantification section names the stored rehearsal or the blocking quantity.
 * It never copies a number into the narrative and never marks the PD ready.
 */
final class OpenPdCalculationNote {
    record Note(String period, String situation, String causeCode, String cause,
                String beTco2e, String peTco2e, String leTco2e, String erDisplay,
                boolean usableForE8, boolean marksDocumentReady, String text,
                OpenPdCalculationProvenance.View provenance) {}

    private OpenPdCalculationNote() {}

    static Note absent() {
        return new Note("M1", "ABSENT", null, null, null, null, null, null,
            false, false,
            "Nenhuma tentativa de cálculo está nomeada neste documento. "
                + "Ausência não é zero. O PD não fica pronto.", null);
    }

    static Note restricted() {
        return new Note(null, "RESTRICTED", null, null, null, null, null, null,
            false, false, "Memória protegida. Esta conta não tem acesso ao cálculo. "
                + "O PD não fica pronto.", null);
    }

    static Note from(OpenE8ProjectRuns.Attempt run) {
        return from(run, null);
    }

    static Note from(OpenE8ProjectRuns.Attempt run, OpenPdCalculationProvenance.View provenance) {
        if (run == null) return absent();
        var memory = run.memory();
        if ("REVIEWED_INTERNAL".equals(run.state()) && memory != null
                && !memory.usableForE8() && !run.canIssueVcu() && !run.stale()) {
            return new Note(run.period(), "MEMORY", null, null,
                memory.beTco2e(), memory.peTco2e(), memory.leTco2e(), memory.erDisplay(),
                false, false,
                "Ensaio interno da memória do servidor. Não é resultado do documento "
                    + "nem emissão de crédito. O PD não fica pronto.", provenance);
        }
        var cause = cause(run.gates());
        return new Note(run.period(), "BLOCKED",
            cause == null ? null : cause.code(),
            cause == null ? "A tentativa não produziu número." : cause.reason(),
            null, null, null, null, false, false,
            "O documento nomeia o bloqueio e não escreve zero. O PD não fica pronto.", null);
    }

    private static OpenCalculationPreflight.Gate cause(List<OpenCalculationPreflight.Gate> gates) {
        if (gates == null || gates.isEmpty()) return null;
        return gates.stream().min(Comparator.comparingInt(gate -> switch (gate.state()) {
            case "MISSING", "MISSING_BINDING" -> 0;
            case "REVIEW_REQUIRED" -> 1;
            default -> 2;
        })).orElse(null);
    }
}
