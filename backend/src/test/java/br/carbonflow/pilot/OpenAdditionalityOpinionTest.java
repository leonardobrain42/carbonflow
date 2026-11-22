package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class OpenAdditionalityOpinionTest {
    @Test
    void positiveListDeclarationAndAnEarlyCommitmentStayHumanOpinions() {
        var market = OpenMarketPenetration.forOpenNetwork();
        assertFalse(market.closesApplicability());
        var readiness = new OpenApplicability.Readiness(
            "OPEN_LDV_DCFC_SIN_BR_CANDIDATE", "OPEN_CANDIDATE", "DECLARED_REVIEW_PENDING",
            "PENDING_NORMATIVE_REVIEW", true, false, false, java.util.List.of(),
            java.util.List.of(), market);
        var opinion = OpenAdditionalityOpinion.assess(readiness, Map.of(
            "route", "Lista positiva VMD0049 a verificar",
            "considered", "2027-01-01",
            "commitment", "2026-12-20"));
        assertEquals("Lista positiva VMD0049 a verificar", opinion.declarationRoute());
        assertFalse(opinion.declarationIsOpinion());
        assertFalse(opinion.marketTestClosesApplicability());
        assertFalse(opinion.concludesAdditionality());
        assertFalse(opinion.usableForE8());
        assertEquals(3, opinion.criteria().size());
        assertTrue(opinion.criteria().stream().allMatch(item ->
            item.humanOpinion() && !item.concludes() && "HUMAN_OPINION".equals(item.state())));
        assertTrue(opinion.criteria().stream().anyMatch(item ->
            "CHRONOLOGY".equals(item.code()) && item.text().contains("não reprova nem aprova")));
        assertTrue(opinion.criteria().stream().noneMatch(item -> "MARKET_ROUTE".equals(item.code())));
    }

    @Test
    void missingDatesAreNotARejectionAndAClosedMarketTestStillDoesNotConclude() {
        var absent = OpenAdditionalityOpinion.assess(null, Map.of());
        assertNull(absent.declarationRoute());
        assertFalse(absent.concludesAdditionality());
        assertTrue(absent.criteria().stream().filter(item -> "CHRONOLOGY".equals(item.code()))
            .allMatch(item -> item.text().contains("não é zero nem reprovação")));

        var closedMarket = new OpenMarketPenetration.Decision(
            "VMD0049 v1.1 Tabela 1", "0,7", 2024, "7,5", "30", true,
            "CITATION_NOT_SERIES", "ABSENT", "citação", "ausente",
            "PENDING_OFFICIAL_DATA", true, false, "hipótese");
        var readiness = new OpenApplicability.Readiness(
            "OPEN", "OPEN_CANDIDATE", "EVIDENCE_PENDING", "PENDING_NORMATIVE_REVIEW",
            true, false, false, java.util.List.of(), java.util.List.of(), closedMarket);
        var opinion = OpenAdditionalityOpinion.assess(readiness, Map.of(
            "route", "Lista positiva VMD0049 a verificar",
            "considered", "2027-03-01",
            "commitment", "2027-04-01"));
        assertTrue(opinion.marketTestClosesApplicability());
        assertFalse(opinion.concludesAdditionality());
        assertFalse(opinion.usableForE8());
        assertTrue(opinion.criteria().stream().filter(item -> "CHRONOLOGY".equals(item.code()))
            .allMatch(item -> item.text().contains("não são o parecer")));
    }
}
