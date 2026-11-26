package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

/** VM0038 v1.1, section 6, Table 1: CO2 always; CH4/N2O optional; what the baseline includes the project includes. */
class FuelBoundariesTest {
    @Test
    void presetsFollowTable1AndAreConsistent() {
        for (var preset : FuelBoundaries.PRESETS) {
            assertEquals(List.of(), FuelBoundaries.inconsistencies(preset.baselineGases(), preset.projectGases()), preset.id());
            assertEquals("CANDIDATE", preset.status());
            assertEquals("VM0038@1.1", preset.method());
        }
        assertEquals(List.of("CO2"), FuelBoundaries.preset("CO2_ONLY").baselineGases());
        assertNull(FuelBoundaries.preset("CO2E_ONLY"));
    }

    @Test
    void inconsistentBoundariesAreNamed() {
        assertFalse(FuelBoundaries.inconsistencies(List.of("CH4"), List.of("CH4")).isEmpty()); // CO2 is mandatory
        assertFalse(FuelBoundaries.inconsistencies(List.of("CO2", "CH4"), List.of("CO2")).isEmpty()); // baseline gas missing in project
        assertTrue(FuelBoundaries.inconsistencies(List.of("CO2"), List.of("CO2", "N2O")).isEmpty()); // project may add
        assertFalse(FuelBoundaries.inconsistencies(List.of("CO2", "SF6"), List.of("CO2", "SF6")).isEmpty());
    }
}
