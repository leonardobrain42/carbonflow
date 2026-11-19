package br.carbonflow.pilot;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;

class PlatformSourceReadinessTest {
    @Test
    void missingMetadataIsNotApproved() {
        assertEquals("MISSING_SOURCE_METADATA", PlatformSourceReadiness.status("NORMATIVE", false, false));
        assertEquals("METADATA_ONLY", PlatformSourceReadiness.status("NORMATIVE", true, false));
        assertEquals("REFERENCE_AUTHORIZED_NOT_ADMITTED",
                PlatformSourceReadiness.status("NORMATIVE", true, true));
        assertEquals("PROJECT_SPECIFIC_EVIDENCE",
                PlatformSourceReadiness.status("PROJECT_EVIDENCE", false, false));
    }

    @Test
    void advisoryTextCannotClaimAdmission() {
        String action = PlatformSourceReadiness.nextAction("REFERENCE_AUTHORIZED_NOT_ADMITTED");
        org.junit.jupiter.api.Assertions.assertTrue(action.contains("admissibilidade técnica"));
    }
}
