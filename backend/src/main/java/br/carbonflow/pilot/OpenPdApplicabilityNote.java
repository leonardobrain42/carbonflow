package br.carbonflow.pilot;

/**
 * The applicability section names the VMD0049 citation beside the §4 test.
 * The citation does not close the test, and the PD stays unready.
 */
final class OpenPdApplicabilityNote {
    record Note(String situation, String citation, String citedStockPercent, int citedVintageYear,
                String stockLimitPercent, String salesLimitPercent, boolean citationBelowStockLimit,
                String stockSeriesLabel, String salesSeriesLabel, boolean closesApplicability,
                boolean usableForE8, boolean marksDocumentReady, String text) {}

    private OpenPdApplicabilityNote() {}

    static Note from(OpenApplicability.Readiness readiness) {
        var known = OpenMarketPenetration.forOpenNetwork();
        var market = readiness == null ? null : readiness.market();
        boolean named = market != null;
        return new Note(
            named ? "CITATION" : "UNDECLARED",
            known.citation(),
            known.citedStockPercent(),
            known.citedVintageYear(),
            known.stockLimitPercent(),
            known.salesLimitPercent(),
            known.citationBelowStockLimit(),
            named ? market.stockSeriesLabel() : "Ausente. Ausência não é zero.",
            named ? market.salesSeriesLabel() : "Ausente. Ausência não é zero.",
            false,
            false,
            false,
            named
                ? "A citação de 0,7% de estoque em 2024 na VMD0049 não fecha o teste de 7,5% e 30%. "
                    + "Ela não é a série oficial deste documento. O PD não fica pronto."
                : "A declaração de rede aberta ainda não nomeia o teste. "
                    + "A citação de 0,7% de 2024 não o fecharia. Ausência não é zero. O PD não fica pronto.");
    }
}
