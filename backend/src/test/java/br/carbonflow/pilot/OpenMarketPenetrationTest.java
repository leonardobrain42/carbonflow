package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;

class OpenMarketPenetrationTest {
    @Test
    void vmdCitationStaysUnderTheStockLimitAndDoesNotCloseTheApplicabilityTest() {
        var cited = OpenMarketPenetration.forOpenNetwork();
        assertEquals("0,7", cited.citedStockPercent());
        assertEquals(2024, cited.citedVintageYear());
        assertEquals("7,5", cited.stockLimitPercent());
        assertEquals("30", cited.salesLimitPercent());
        assertTrue(cited.citationBelowStockLimit());
        assertTrue(OpenMarketPenetration.CITED_STOCK.compareTo(OpenMarketPenetration.STOCK_LIMIT) < 0);
        assertEquals("CITATION_NOT_SERIES", cited.stockSeries());
        assertEquals("ABSENT", cited.salesSeries());
        assertEquals("PENDING_OFFICIAL_DATA", cited.outcome());
        assertFalse(cited.closesApplicability());
        assertFalse(cited.usableForE8());
        assertTrue(cited.reason().contains("não fecha o teste"));
        assertTrue(cited.reason().contains("não vale zero"));
        assertTrue(cited.salesSeriesLabel().contains("não é zero"));
    }

    @Test
    void absenceAndZeroAndAnUnadmittedPairEachStayOpen() {
        var absent = OpenMarketPenetration.assess(null, null);
        assertEquals("ABSENT", absent.stockSeries());
        assertEquals("ABSENT", absent.salesSeries());
        assertFalse(absent.closesApplicability());

        var zero = OpenMarketPenetration.assess(
            new OpenMarketPenetration.Offer(BigDecimal.ZERO, 2024, "VMD0049 v1.1"),
            new OpenMarketPenetration.Offer(BigDecimal.ZERO, 2024, "SENATRAN"));
        assertEquals("DECLARED_ZERO", zero.stockSeries());
        assertEquals("DECLARED_ZERO", zero.salesSeries());
        assertFalse(zero.closesApplicability());
        assertNotEquals("ABSENT", zero.salesSeries());

        var underBothLimits = OpenMarketPenetration.assess(
            new OpenMarketPenetration.Offer(new BigDecimal("4.0"), 2025, "SENATRAN"),
            new OpenMarketPenetration.Offer(new BigDecimal("10"), 2025, "SENATRAN"));
        assertEquals("NOT_ADMITTED", underBothLimits.stockSeries());
        assertEquals("NOT_ADMITTED", underBothLimits.salesSeries());
        assertTrue(new BigDecimal("4.0").compareTo(OpenMarketPenetration.STOCK_LIMIT) < 0);
        assertTrue(new BigDecimal("10").compareTo(OpenMarketPenetration.SALES_LIMIT) < 0);
        assertFalse(underBothLimits.closesApplicability());
        assertFalse(underBothLimits.usableForE8());
    }
}
