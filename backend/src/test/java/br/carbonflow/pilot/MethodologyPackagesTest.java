package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.junit.jupiter.api.Test;

/** The package catalog describes what the platform can read; it admits nothing and never lists a test package. */
class MethodologyPackagesTest {
    @Test
    void catalogListsOnlyConfiguredPackagesWithVersionAndDigest() {
        var catalog = MethodologyPackages.current();
        assertEquals(1, catalog.version());
        assertTrue(catalog.digest().matches("[0-9a-f]{64}"));
        assertEquals(1, catalog.packages().size());
        var vm = catalog.packages().get(0);
        assertEquals("VM0038_V1_1", vm.packageId());
        assertEquals("VCS", vm.program());
        assertEquals("VM0038", vm.methodology());
        assertEquals("1.1", vm.edition());
        assertEquals(1, vm.version());
        assertTrue(vm.digest().matches("[0-9a-f]{64}"));
        // The catalog restates the existing limits: nothing is admitted and no external act is enabled.
        assertEquals("NOT_ENABLED", vm.capabilities().get("calculationAdmission"));
        assertEquals("NOT_ENABLED", vm.capabilities().get("externalActs"));
        assertEquals(1, MethodologyPackages.options().size());
        assertEquals("VM0038", MethodologyPackages.options().get(0).value());
    }

    @Test
    void everyPackageActivityBelongsToThePlatformVocabulary() {
        Set<String> vocabulary = MethodologyPackages.ACTIVITY_TYPES.stream()
                .map(MethodologyPackages.ActivityType::value).collect(Collectors.toSet());
        assertEquals(MethodologyPackages.ACTIVITY_TYPES.size(), vocabulary.size());
        for (var item : MethodologyPackages.current().packages()) {
            assertTrue(vocabulary.containsAll(item.activities()), item.packageId());
        }
        assertTrue(vocabulary.contains("DESCONHECIDO"));
    }

    @Test
    void anyChangeToADescriptorMovesItsDigest() {
        var vm = ProjectJourney.VM0038;
        var caps = Map.of("triageSchema", "AVAILABLE", "calculationAdmission", "NOT_ENABLED", "externalActs", "NOT_ENABLED");
        String listed = MethodologyPackages.current().packages().get(0).digest();
        String same = MethodologyPackages.digestOf(vm, "VCS", 1, "FIRST_SLICE_OPEN_BR_LDV_BEV_DCFC_SIN", caps);
        assertEquals(listed, same);
        assertNotEquals(listed, MethodologyPackages.digestOf(vm, "VCS", 2, "FIRST_SLICE_OPEN_BR_LDV_BEV_DCFC_SIN", caps));
        assertNotEquals(listed, MethodologyPackages.digestOf(vm, "OTHER", 1, "FIRST_SLICE_OPEN_BR_LDV_BEV_DCFC_SIN", caps));
        assertNotEquals(listed, MethodologyPackages.digestOf(vm, "VCS", 1, "FIRST_SLICE_OPEN_BR_LDV_BEV_DCFC_SIN",
                Map.of("triageSchema", "AVAILABLE", "calculationAdmission", "ENABLED", "externalActs", "NOT_ENABLED")));
    }
}
