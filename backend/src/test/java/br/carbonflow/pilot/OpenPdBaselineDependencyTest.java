package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

class OpenPdBaselineDependencyTest {
    private static OpenPdBaselineDependency.Reference ref(int version, String sha, boolean stale) {
        return new OpenPdBaselineDependency.Reference(version, sha.repeat(64), 2026, "COMBINED",
            "PREPARATORY_NOT_ADMITTED", stale);
    }

    @Test
    void nothingToSayWithoutAPinnedOrCurrentReference() {
        assertNull(OpenPdBaselineDependency.evaluate(null, null));
    }

    @Test
    void aVersionWrittenBeforeTheReferenceIsInformedButNeverOutdated() {
        var dep = OpenPdBaselineDependency.evaluate(null, ref(1, "a", false));
        assertEquals("REFERENCE_NOT_CITED", dep.state());
        assertFalse(dep.outdated(), "Only dependents go stale (BL-T08).");
        assertNull(dep.pinnedVersion());
        assertEquals(1, dep.currentVersion());
    }

    @Test
    void theSameExactReferenceIsCurrentAndANewVersionOrHashSupersedesIt() {
        var current = OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(1, "a", false));
        assertEquals("REFERENCE_CURRENT", current.state());
        assertFalse(current.outdated());

        var newer = OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(2, "b", false));
        assertEquals("REFERENCE_SUPERSEDED", newer.state());
        assertTrue(newer.outdated());
        assertEquals(1, newer.pinnedVersion());
        assertEquals(2, newer.currentVersion());
        assertTrue(newer.text().contains("continua íntegra"));

        var sameNumberOtherHash = OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(1, "b", false));
        assertEquals("REFERENCE_SUPERSEDED", sameNumberOtherHash.state(), "The hash is part of the exact reference.");
    }

    @Test
    void aStaleOrMissingReferenceBlocksOnlyTheDependentVersion() {
        var stale = OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(1, "a", true));
        assertEquals("REFERENCE_STALE", stale.state());
        assertTrue(stale.outdated());
        var missing = OpenPdBaselineDependency.evaluate(ref(1, "a", false), null);
        assertEquals("REFERENCE_MISSING", missing.state());
        assertTrue(missing.outdated());
    }

    @Test
    void textsNeverCarryValuesOrPercentages() {
        for (var dep : new OpenPdBaselineDependency.Dependency[]{
                OpenPdBaselineDependency.evaluate(null, ref(1, "a", false)),
                OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(2, "b", false)),
                OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(1, "a", true)),
                OpenPdBaselineDependency.evaluate(ref(1, "a", false), null),
                OpenPdBaselineDependency.evaluate(ref(1, "a", false), ref(1, "a", false))}) {
            assertFalse(dep.text().contains("%") || dep.text().contains("AFEC") || dep.text().contains("MPG"), dep.text());
        }
    }
}
