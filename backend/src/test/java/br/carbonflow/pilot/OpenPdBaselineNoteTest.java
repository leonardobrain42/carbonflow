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

    @Test
    void theExactExAnteReferenceIsNamedWithoutAMonitoredSelectionAndWithoutAdmission() {
        var none = OpenPdBaselineNote.from(null, (OpenBaselineFleetSelections.View) null, Map.of());
        assertNull(none.exAnte(), "Sem referência ex ante não há objeto nem zero.");
        assertEquals("ABSENT", none.situation());

        var note = OpenPdBaselineNote.from(null, exAnte("PREPARATORY_NOT_ADMITTED", false, false), Map.of());
        assertEquals("ABSENT", note.situation(), "A seleção monitorada continua ausente: sem fallback.");
        var ref = note.exAnte();
        assertNotNull(ref);
        assertEquals(3, ref.version());
        assertEquals(2026, ref.observationYear());
        assertEquals("COMBINED", ref.cycle());
        assertEquals("d".repeat(64), ref.selectionSha256());
        assertEquals("PREPARATORY_NOT_ADMITTED", ref.state());
        assertFalse(ref.stale());
        assertFalse(ref.usableForE8());
        assertFalse(note.usableForE8());
        assertFalse(note.admitsBaseline());
        assertFalse(note.marksDocumentReady());
        assertTrue(ref.text().contains("versão 3"));
        assertTrue(ref.text().contains("não alimenta o E8"));
        assertTrue(ref.text().contains("não copia estoque nem rating"));
        assertFalse(ref.text().contains("AFEC"));
        assertFalse(ref.text().contains("%"));

        var stale = OpenPdBaselineNote.from(null, exAnte("STALE_NOT_ADMITTED", true, false), Map.of()).exAnte();
        assertEquals("STALE_NOT_ADMITTED", stale.state());
        assertTrue(stale.stale());
        assertTrue(stale.text().contains("não está corrente"));

        var forced = OpenPdBaselineNote.from(null, exAnte("PREPARATORY_NOT_ADMITTED", false, true), Map.of()).exAnte();
        assertEquals("STALE_NOT_ADMITTED", forced.state(), "Seleção marcada utilizável nunca vira referência corrente.");
        assertFalse(forced.usableForE8());
    }

    private static OpenBaselineFleetSelections.View exAnte(String state, boolean stale, boolean usable) {
        return new OpenBaselineFleetSelections.View(
            UUID.randomUUID(), "project", 3, "PD", java.time.LocalDate.parse("2026-06-01"), 2026,
            java.time.LocalDate.parse("2026-07-01"), "REFERENCE_DATE_CALENDAR_YEAR",
            UUID.randomUUID(), UUID.randomUUID(), "COMBINED", "a".repeat(64), "b".repeat(64), "d".repeat(64),
            "justificativa", state, stale, usable, OffsetDateTime.parse("2026-11-27T12:00:00Z"), List.of());
    }

    private static OpenE8FleetSelections.View view(String state, boolean stale, boolean usable) {
        return new OpenE8FleetSelections.View(
            UUID.randomUUID(), "project", "M1", 1,
            UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
            2027, "COMBINED", "a", "b", "c", state, stale, usable,
            OffsetDateTime.parse("2026-11-22T15:00:00Z"), List.of());
    }
}
