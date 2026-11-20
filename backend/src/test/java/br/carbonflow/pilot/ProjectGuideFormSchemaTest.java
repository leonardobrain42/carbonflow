package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ProjectGuideFormSchemaTest {
    private static ProjectGuides.Guide vintage() {
        var field = new ProjectGuides.Field("vintage", "Ano", "year", List.of(), "Informe o ano.");
        return new ProjectGuides.Guide("quantification", "Quantificação", "Rascunho",
                List.of(field), 2, 0, Map.of(), List.of("Ano"), List.of(), true);
    }

    @Test
    void validatesStructuredYearOnServerAndNeverAcceptsArbitraryTextAsYear() {
        var guide = vintage();
        assertEquals(Map.of("vintage","2027"), ProjectGuides.normalize(guide, Map.of("vintage", " 2027 ")));
        for (String invalid : List.of("20xx", "2030-01-01", "1899", "2101", "2O27", "2027.0")) {
            PilotProblem problem = assertThrows(PilotProblem.class,
                    () -> ProjectGuides.normalize(guide, Map.of("vintage", invalid)));
            assertEquals("RESPOSTA_INVALIDA", problem.code);
        }
    }

    @Test
    void identifiesAmbiguousLegacyValueWithoutOverwritingOriginalRevision() {
        var guide = vintage();
        var original = Map.of("vintage", "20xx");
        var historic = ProjectGuides.view(guide.id(), guide.title(), guide.guidance(),
                guide.fields(), 1, 1, original, false);
        assertEquals(original, historic.answers());
        assertEquals(1, historic.definitionVersion());
        assertEquals(List.of("vintage"), historic.legacyUnstructured());
        assertEquals(List.of("Ano"), historic.missing());
        assertThrows(PilotProblem.class, () -> ProjectGuides.normalize(guide, original));
        assertEquals(Map.of("vintage", "20xx"), original);
    }

    @Test
    void preservesLegacySelectAndDatesWithoutInventingReferences() {
        var select = new ProjectGuides.Field("incidence", "Incidência", "select",
                List.of("A confirmar", "Aplicável", "Não aplicável"), null);
        var date = new ProjectGuides.Field("plannedDate", "Previsão", "date", List.of(), null);
        var guide = new ProjectGuides.Guide("applicability", "Aplicabilidade", "Rascunho",
                List.of(select, date), 2, 0, Map.of(), List.of(), List.of(), true);
        assertEquals(Map.of("incidence", "Aplicável", "plannedDate", "2027-02-28"),
                ProjectGuides.normalize(guide, Map.of("incidence","Aplicável","plannedDate","2027-02-28")));
        assertThrows(PilotProblem.class, () -> ProjectGuides.normalize(guide,
                Map.of("incidence","Talvez", "plannedDate","2027-02-28")));
        assertThrows(PilotProblem.class, () -> ProjectGuides.normalize(guide,
                Map.of("plannedDate","2027-02-30")));
    }
}
