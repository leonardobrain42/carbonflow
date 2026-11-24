package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenPdCalculationNoteTest {
    @Test
    void rehearsalMemoryIsNamedAndDoesNotReadyTheDocument() {
        var note = OpenPdCalculationNote.from(run("REVIEWED_INTERNAL", false, memory(false), List.of()));
        assertEquals("MEMORY", note.situation());
        assertEquals("4.05", note.beTco2e());
        assertEquals("1.5", note.peTco2e());
        assertEquals("0", note.leTco2e());
        assertEquals("2.55", note.erDisplay());
        assertFalse(note.usableForE8());
        assertFalse(note.marksDocumentReady());
        assertTrue(note.text().contains("O PD não fica pronto"));
    }

    @Test
    void aBlockAndAnAdmittedMemoryStayWithoutNumbers() {
        var blocked = OpenPdCalculationNote.from(run("BLOCKED", false, null, List.of(
            OpenE8RunDecision.gate("IR", "MISSING"),
            OpenE8RunDecision.gate("ED", "NOT_ADMITTED"))));
        assertEquals("BLOCKED", blocked.situation());
        assertEquals("IR", blocked.causeCode());
        assertNull(blocked.beTco2e());
        assertNull(blocked.erDisplay());
        assertTrue(blocked.text().contains("não escreve zero"));
        assertFalse(blocked.marksDocumentReady());

        var admitted = OpenPdCalculationNote.from(run("REVIEWED_INTERNAL", false, memory(true), List.of()));
        assertEquals("BLOCKED", admitted.situation());
        assertNull(admitted.beTco2e());
        assertFalse(admitted.usableForE8());

        var missing = OpenPdCalculationNote.from(null);
        assertEquals("ABSENT", missing.situation());
        assertTrue(missing.text().contains("Ausência não é zero"));
        assertFalse(missing.marksDocumentReady());
    }

    @Test
    void staleAndRestrictedReadsNeverExposeTheMemoryOrItsReferences() {
        var stale = OpenPdCalculationNote.from(run("STALE", true, memory(false), List.of(
            OpenE8RunDecision.gate("RUN_REFERENCES", "STALE"))));
        assertEquals("BLOCKED", stale.situation());
        assertNull(stale.beTco2e());
        assertNull(stale.provenance());
        var restricted = OpenPdCalculationNote.restricted();
        assertEquals("RESTRICTED", restricted.situation());
        assertNull(restricted.period());
        assertNull(restricted.beTco2e());
        assertNull(restricted.provenance());
        assertFalse(restricted.marksDocumentReady());
    }

    private static OpenE8RunDecision.Memory memory(boolean usable) {
        return new OpenE8RunDecision.Memory("engine", "PURE_MATH_TEST_ONLY_NOT_ADMITTED", usable,
            "20", "30", "4.05", "1.5", "0", "1", "2.55", "2.55", List.of(), null);
    }

    private static OpenE8ProjectRuns.Attempt run(String state, boolean stale,
            OpenE8RunDecision.Memory memory, List<OpenCalculationPreflight.Gate> gates) {
        return new OpenE8ProjectRuns.Attempt(UUID.randomUUID(), "project", "M1", 2,
            state, stale, false, "PURE_MATH_TEST_ONLY_NOT_ADMITTED",
            null, null, "abc", gates, List.of(), memory, OffsetDateTime.parse("2026-11-22T12:00:00Z"),
            null, null);
    }
}
