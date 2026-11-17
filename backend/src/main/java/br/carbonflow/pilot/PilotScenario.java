package br.carbonflow.pilot;

import java.time.OffsetDateTime;
import java.util.List;

final class PilotScenario {
    private static final Fixture.Reference OPERATION = new Fixture.Reference("SIM-OPERACAO-M2", 1);
    private static final Fixture.Reference REVISION = new Fixture.Reference("SIM-REV-M1", 1);
    private static final Fixture.Reference SETTLEMENT = new Fixture.Reference("SIM-QUITACAO-I1", 1);

    record Checkpoint(OffsetDateTime effectiveAt, String m1, String m2, String v1, String i1,
                      List<Fixture.Reference> simulatedEvidence) {}

    List<Checkpoint> demonstrate(CarbonCalculator.Results result, Fixture.IssuanceStatus issuance) {
        var m2 = result.periods().get(1);
        var window = new VintageWindows.Window(m2.start(), m2.endExclusive());
        var opening = window.opening();
        var v1Review = opening.plusDays(1);
        var i1Pending = opening.plusDays(10);
        if (issuance == null || !"SIM-V1".equals(issuance.sameVerificationRequired())
                || !new Fixture.Reference("SIM-APROVA-V1", 1).equals(issuance.approvalEvidence())
                || !new Fixture.Reference("SIM-ATIVA-V1", 1).equals(issuance.activationEvidence())) {
            throw PilotProblem.invalid("PROVA_INSUFICIENTE", "issuanceStatus", "TX-E3-37/48",
                    "Faltam as referências simuladas de aprovação ou ativação de V1.");
        }

        // TX-E3-41 e 45. M2 abre na fronteira mesmo com M1 em conferência.
        // O marco posterior de fechamento de M1 pressupõe a memória validada.
        return List.of(
                new Checkpoint(opening, "EM_CONFERENCIA", collectionAt(window, opening, OPERATION),
                        "NOVO", "NOVO", List.of(OPERATION)),
                new Checkpoint(v1Review, "FECHADO", collectionAt(window, v1Review, OPERATION),
                        "EM_REVISAO", "NOVO", List.of(REVISION, OPERATION)),
                new Checkpoint(i1Pending, "FECHADO", collectionAt(window, i1Pending, OPERATION),
                        "APROVADO", "AGUARDANDO_DEPOSITO",
                        List.of(REVISION, OPERATION, issuance.approvalEvidence(), issuance.activationEvidence(), SETTLEMENT))
        );
    }

    // TX-E3-41/42. A regra de coleta usa a janela própria e a prova de operação.
    static String collectionAt(VintageWindows.Window window, OffsetDateTime at, Fixture.Reference operation) {
        if (!OPERATION.equals(operation) || at.isBefore(window.opening())) {
            return "PREVISTO";
        }
        return window.contains(at) ? "COLETANDO" : "EM_CONFERENCIA";
    }
}
