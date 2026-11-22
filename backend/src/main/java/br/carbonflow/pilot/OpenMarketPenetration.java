package br.carbonflow.pilot;

import java.math.BigDecimal;

/**
 * VM0038 v1.1 §4 (1)(b) stays open until admitted official series exist.
 * The VMD0049 v1.1 table citation is not that series.
 */
final class OpenMarketPenetration {
    static final BigDecimal CITED_STOCK = new BigDecimal("0.7");
    static final int CITED_YEAR = 2024;
    static final BigDecimal STOCK_LIMIT = new BigDecimal("7.5");
    static final BigDecimal SALES_LIMIT = new BigDecimal("30");

    record Offer(BigDecimal percent, Integer year, String source) {}

    record Decision(
        String citation,
        String citedStockPercent,
        int citedVintageYear,
        String stockLimitPercent,
        String salesLimitPercent,
        boolean citationBelowStockLimit,
        String stockSeries,
        String salesSeries,
        String stockSeriesLabel,
        String salesSeriesLabel,
        String outcome,
        boolean closesApplicability,
        boolean usableForE8,
        String reason) {}

    private OpenMarketPenetration() {}

    /** The open-network screen shows the known citation beside an absent sales series. */
    static Decision forOpenNetwork() {
        return assess(new Offer(CITED_STOCK, CITED_YEAR, "VMD0049 v1.1"), null);
    }

    static Decision assess(Offer stock, Offer sales) {
        boolean below = CITED_STOCK.compareTo(STOCK_LIMIT) < 0;
        String stockState = seriesState(stock);
        String salesState = seriesState(sales);
        return new Decision(
            "VMD0049 v1.1 Tabela 1",
            "0,7",
            CITED_YEAR,
            "7,5",
            "30",
            below,
            stockState,
            salesState,
            label(stockState),
            label(salesState),
            "PENDING_OFFICIAL_DATA",
            false,
            false,
            "A citação de 0,7% de estoque em 2024 na VMD0049 v1.1 não fecha o teste de aplicabilidade "
                + "da VM0038 §4 (1)(b). O teste pede série oficial de estoque nacional abaixo de 7,5% "
                + "e média trienal de vendas abaixo de 30%. Esta citação não é essa série. "
                + "A ausência de vendas não vale zero e não reprova o projeto.");
    }

    private static String seriesState(Offer offer) {
        if (offer == null || offer.percent == null) return "ABSENT";
        if (offer.percent.compareTo(BigDecimal.ZERO) == 0) return "DECLARED_ZERO";
        if (isCitation(offer)) return "CITATION_NOT_SERIES";
        return "NOT_ADMITTED";
    }

    private static boolean isCitation(Offer offer) {
        return offer.percent.compareTo(CITED_STOCK) == 0
            && offer.year != null && offer.year == CITED_YEAR
            && offer.source != null && offer.source.toUpperCase().contains("VMD0049");
    }

    private static String label(String state) {
        return switch (state) {
            case "ABSENT" -> "Ausente. Ausência não é zero.";
            case "DECLARED_ZERO" -> "Zero declarado não substitui a série ausente e não fecha o teste.";
            case "CITATION_NOT_SERIES" -> "Citação da lista positiva. Não é a série oficial do teste.";
            default -> "Número sem série oficial admitida. Não entra no teste.";
        };
    }
}
