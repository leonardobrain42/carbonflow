package br.carbonflow.pilot;

import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class PlatformSourceReadinessTest {
    @Test
    void statesNeverMistakeMetadataForTechnicalAdmission() {
        assertEquals("MISSING_SOURCE_METADATA", PlatformSourceReadiness.status("NORMATIVE", false, false));
        assertEquals("METADATA_ONLY", PlatformSourceReadiness.status("NORMATIVE", true, false));
        assertEquals("SCOPED_REFERENCE_NOT_ADMITTED", PlatformSourceReadiness.status("NORMATIVE", true, true));
        assertEquals("PROJECT_SPECIFIC_EVIDENCE",
                PlatformSourceReadiness.status("PROJECT_EVIDENCE", false, false));
        assertEquals("VERSION_SELECTION_REQUIRED",
                PlatformSourceReadiness.status("DATASET", "2026", "YEAR", false));
        assertEquals("VERSION_SELECTION_REQUIRED",
                PlatformSourceReadiness.status("DATASET", "2026", "LATEST", true));
        assertEquals("METADATA_ONLY",
                PlatformSourceReadiness.status("NORMATIVE", "1.1", "EXACT", false));
    }

    @Test
    void advisoryNeverClaimsPublicationOrExternalApproval() {
        String action = PlatformSourceReadiness.nextAction("SCOPED_REFERENCE_NOT_ADMITTED");
        assertTrue(action.contains("admissibilidade"));
        assertFalse(action.contains("aprovado para cálculo"));
    }
}
