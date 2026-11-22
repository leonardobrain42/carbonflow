package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;

import org.junit.jupiter.api.Test;

class OpenPdApplicabilityNoteTest {
    @Test
    void theCitationStaysUnderTheLimitAndDoesNotReadyTheDocument() {
        var market = OpenMarketPenetration.forOpenNetwork();
        assertTrue(market.citationBelowStockLimit());
        assertFalse(market.closesApplicability());
        var readiness = new OpenApplicability.Readiness(
            "OPEN", "OPEN_CANDIDATE", "EVIDENCE_PENDING", "PENDING_NORMATIVE_REVIEW",
            true, false, false, List.of(), List.of(), market);
        var note = OpenPdApplicabilityNote.from(readiness);
        assertEquals("CITATION", note.situation());
        assertEquals("0,7", note.citedStockPercent());
        assertEquals(2024, note.citedVintageYear());
        assertEquals("7,5", note.stockLimitPercent());
        assertEquals("30", note.salesLimitPercent());
        assertTrue(note.citationBelowStockLimit());
        assertFalse(note.closesApplicability());
        assertFalse(note.usableForE8());
        assertFalse(note.marksDocumentReady());
        assertTrue(note.text().contains("não fecha o teste"));
        assertTrue(note.salesSeriesLabel().contains("não é zero"));
    }

    @Test
    void anUndeclaredProjectAndAClosedMarketStillLeaveTheDocumentOpen() {
        var missing = OpenPdApplicabilityNote.from(null);
        assertEquals("UNDECLARED", missing.situation());
        assertEquals("0,7", missing.citedStockPercent());
        assertFalse(missing.closesApplicability());
        assertFalse(missing.marksDocumentReady());
        assertTrue(missing.text().contains("não o fecharia"));
        assertTrue(missing.text().contains("Ausência não é zero"));

        var forced = new OpenMarketPenetration.Decision(
            "VMD0049 v1.1 Tabela 1", "0,7", 2024, "7,5", "30", true,
            "CITATION_NOT_SERIES", "ABSENT", "citação", "ausente",
            "PENDING_OFFICIAL_DATA", true, false, "hipótese");
        var readiness = new OpenApplicability.Readiness(
            "OPEN", "OPEN_CANDIDATE", "EVIDENCE_PENDING", "PENDING_NORMATIVE_REVIEW",
            true, false, false, List.of(), List.of(), forced);
        var note = OpenPdApplicabilityNote.from(readiness);
        assertTrue(forced.closesApplicability());
        assertFalse(note.closesApplicability());
        assertFalse(note.marksDocumentReady());
        assertFalse(note.usableForE8());
    }
}
