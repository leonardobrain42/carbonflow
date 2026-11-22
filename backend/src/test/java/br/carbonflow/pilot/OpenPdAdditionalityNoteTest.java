package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class OpenPdAdditionalityNoteTest {
    @Test
    void aPositiveListRouteStaysOutOfTheDocumentConclusion() {
        var market = OpenMarketPenetration.forOpenNetwork();
        var readiness = new OpenApplicability.Readiness(
            "OPEN", "OPEN_CANDIDATE", "DECLARED_REVIEW_PENDING", "PENDING_NORMATIVE_REVIEW",
            true, false, false, java.util.List.of(), java.util.List.of(), market);
        var note = OpenPdAdditionalityNote.from(readiness, Map.of(
            "route", "Lista positiva VMD0049 a verificar",
            "considered", "2027-01-01",
            "commitment", "2026-12-20"));
        assertEquals("Lista positiva VMD0049 a verificar", note.declarationRoute());
        assertFalse(note.declarationIsOpinion());
        assertFalse(note.marketTestClosesApplicability());
        assertFalse(note.concludesAdditionality());
        assertFalse(note.usableForE8());
        assertFalse(note.marksDocumentReady());
        assertEquals(3, note.criteria().size());
        assertTrue(note.criteria().stream().allMatch(item -> item.humanOpinion() && !item.concludes()));
        assertTrue(note.criteria().stream().noneMatch(item -> "MARKET_ROUTE".equals(item.code())));
        assertTrue(note.text().contains("não entra no documento"));
        assertTrue(note.text().contains("O PD não fica pronto"));
    }
}
