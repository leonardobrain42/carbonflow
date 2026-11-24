package br.carbonflow.pilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class OpenIrHandoffRulesTest {
    @Test void eachMetricNeedsExplicitComparisonAndSubstantiveNote() {
        String match="MATCH: A referência corresponde à fonte prevista.";
        String deviation="JUSTIFIED_DEVIATION: A edição prevista foi substituída por nova edição.";
        assertEquals(match,OpenIrHandoffRules.note(match));
        assertEquals(deviation,OpenIrHandoffRules.note(deviation));
        assertThrows(IllegalArgumentException.class,()->OpenIrHandoffRules.note("Apenas um texto sem classificação"));
        assertThrows(IllegalArgumentException.class,()->OpenIrHandoffRules.note("MATCH: curto"));
        assertThrows(IllegalArgumentException.class,()->OpenIrHandoffRules.note("UNRESOLVED: A origem ainda está indeterminada."));
        assertThrows(IllegalArgumentException.class,()->OpenIrHandoffRules.note(null));
    }
}
