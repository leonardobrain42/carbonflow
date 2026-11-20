package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import org.junit.jupiter.api.Test;

/** Candidate-source statuses are never calculation/admission permissions. */
class OpenPreparatoryE8DiagnosticTest {
    private static OpenCalculationPreflight.Gate input(String state){
        return OpenPreparatoryE8Diagnostic.gates("M1",state,
            "FLEET_SELECTION_MISSING").get(0);
    }
    private static OpenCalculationPreflight.Gate fleet(String state){
        return OpenPreparatoryE8Diagnostic.gates("M2","BINDING_MISSING",
            state).get(1);
    }

    @Test void missingBindingsHaveExplicitActions(){
        var gates=OpenPreparatoryE8Diagnostic.gates("M1",
            "BINDING_MISSING","FLEET_SELECTION_MISSING");
        assertEquals(List.of("E8_INPUT_BINDING_M1","E8_FLEET_SELECTION_M1"),
            gates.stream().map(OpenCalculationPreflight.Gate::code).toList());
        assertEquals("MISSING",gates.get(0).state());
        assertEquals("MISSING",gates.get(1).state());
        assertTrue(gates.get(0).reason().contains("Nenhum vínculo"));
        assertTrue(gates.get(1).nextAction().contains("gestor"));
    }

    @Test void internallyCheckedAndSelectedAreNotAdmission(){
        assertEquals("NOT_ADMITTED",input("CANDIDATE_NOT_ADMITTED").state());
        assertEquals("NOT_ADMITTED",fleet("PREPARATORY_NOT_ADMITTED").state());
        assertTrue(input("CANDIDATE_NOT_ADMITTED").reason().contains("nenhum desses parâmetros foi admitido"));
        assertTrue(fleet("PREPARATORY_NOT_ADMITTED").reason().contains("Não há AFEC/MPG admitidos"));
    }

    @Test void staleIsNotConfusedWithAbsence(){
        assertEquals("STALE",input("STALE_NOT_ADMITTED").state());
        assertEquals("STALE",fleet("STALE_NOT_ADMITTED").state());
        assertTrue(input("STALE_NOT_ADMITTED").nextAction().contains("nova versão"));
        assertTrue(fleet("STALE_NOT_ADMITTED").reason().contains("histórico"));
    }

    @Test void unexpectedStatusFailsClosed(){
        assertEquals("REVIEW_REQUIRED",input("ADMITTED").state());
        assertEquals("REVIEW_REQUIRED",fleet("ADMITTED").state());
        assertEquals("REVIEW_REQUIRED",input("UNKNOWN_STATE").state());
        assertEquals("REVIEW_REQUIRED",fleet("UNKNOWN_STATE").state());
    }
}
