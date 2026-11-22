package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenPdBaselineNoteTest {
    @Test
    void aPreparatorySelectionNamesTheYearAndDoesNotAdmitTheBaseline() {
        var selection = view("PREPARATORY_NOT_ADMITTED", false, false);
        var note = OpenPdBaselineNote.from(selection, Map.of("rating", " 18 kWh/100 mi "));
        assertEquals("REFERENCE", note.situation());
        assertEquals("M1", note.period());
        assertEquals(2027, note.observationYear());
        assertEquals("COMBINED", note.cycle());
        assertEquals("18 kWh/100 mi", note.declaredRating());
        assertFalse(note.declarationIsRating());
        assertFalse(note.admitsBaseline());
        assertFalse(note.copiesLicensedRatings());
        assertFalse(note.usableForE8());
        assertFalse(note.marksDocumentReady());
        assertTrue(note.text().contains("referência preparatória"));
        assertTrue(note.text().contains("não substitui o par PBEV"));
        assertFalse(note.text().contains("AFEC"));
        assertFalse(note.text().contains("MPG"));
    }

    @Test
    void absenceAndAStaleOrAdmittedSelectionStayOutOfTheDocument() {
        var missing = OpenPdBaselineNote.from(null, Map.of("rating", "   "));
        assertEquals("ABSENT", missing.situation());
        assertNull(missing.declaredRating());
        assertNull(missing.observationYear());
        assertFalse(missing.declarationIsRating());
        assertFalse(missing.admitsBaseline());
        assertTrue(missing.text().contains("Ausência não é consumo zero"));

        var stale = OpenPdBaselineNote.from(view("STALE_NOT_ADMITTED", true, false), Map.of());
        assertEquals("BLOCKED", stale.situation());
        assertEquals(2027, stale.observationYear());
        assertFalse(stale.admitsBaseline());
        assertFalse(stale.marksDocumentReady());

        var forced = OpenPdBaselineNote.from(view("PREPARATORY_NOT_ADMITTED", false, true), null);
        assertEquals("BLOCKED", forced.situation());
        assertFalse(forced.usableForE8());
        assertFalse(forced.copiesLicensedRatings());
        assertFalse(forced.marksDocumentReady());
    }

    private static OpenE8FleetSelections.View view(String state, boolean stale, boolean usable) {
        return new OpenE8FleetSelections.View(
            UUID.randomUUID(), "project", "M1", 1,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            2027, "COMBINED", "a", "b", "c", state, stale, usable,
            OffsetDateTime.parse("2026-11-22T15:00:00Z"), List.of());
    }
}
