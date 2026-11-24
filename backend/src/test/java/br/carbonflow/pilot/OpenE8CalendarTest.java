package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class OpenE8CalendarTest {
    private static final LocalDate START = LocalDate.parse("2027-02-05");
    private static final OpenE8Calendar.Dates DATES = new OpenE8Calendar.Dates(1,
        new OpenE8Calendar.Event("FIRST-STATION", 1, START, START), null);

    @Test
    void windowsInSameCalendarYearUseSameProjectYear() {
        var first = OpenE8Calendar.resolve(DATES,
            window("2027-02-05T03:00:00Z", "2027-03-01T03:00:00Z"));
        var second = OpenE8Calendar.resolve(DATES,
            window("2027-06-01T03:00:00Z", "2028-01-01T03:00:00Z"));
        var following = OpenE8Calendar.resolve(DATES,
            window("2028-01-01T03:00:00Z", "2028-02-01T03:00:00Z"));
        assertEquals(1, first.projectYear());
        assertEquals(1, second.projectYear());
        assertEquals(2, following.projectYear());
        assertEquals(2027, second.calendarYear());
        assertEquals(2028, following.calendarYear());
    }

    @Test
    void usesLocalYearAndExclusiveEndAtMidnight() {
        var throughMidnight = OpenE8Calendar.resolve(DATES,
            window("2028-01-01T00:00:00Z", "2028-01-01T03:00:00Z"));
        assertEquals(2027, throughMidnight.calendarYear());
        var crossing = OpenE8Calendar.resolve(DATES,
            window("2028-01-01T00:00:00Z", "2028-01-01T04:00:00Z"));
        assertEquals("WINDOW_CROSSES_YEAR", crossing.status());
        assertNull(crossing.projectYear());
    }

    @Test
    void rejectsMissingInconsistentOrSupersededStartAndPreCreditingWindows() {
        var measured = window("2027-03-01T03:00:00Z", "2027-04-01T03:00:00Z");
        assertEquals("WINDOW_MISSING", OpenE8Calendar.resolve(DATES, null).status());
        assertEquals("CREDITING_START_MISSING", OpenE8Calendar.resolve(null, measured).status());
        LocalDate later = START.plusDays(1);
        var conflicting = new OpenE8Calendar.Dates(2, DATES.operationalStart(),
            new OpenE8Calendar.Event("VCS-START", 1, later, later));
        assertEquals("CREDITING_START_CONFLICT", OpenE8Calendar.resolve(conflicting, measured).status());
        var revised = new OpenE8Calendar.Dates(2,
            new OpenE8Calendar.Event("FIRST-STATION", 2, START, later), null);
        assertEquals("CREDITING_START_REVISED", OpenE8Calendar.resolve(revised, measured).status());
        assertEquals("WINDOW_BEFORE_CREDITING", OpenE8Calendar.resolve(DATES,
            window("2027-02-04T03:00:00Z", "2027-02-05T03:00:00Z")).status());
    }

    private static OpenE8Calendar.Window window(String from, String until) {
        return new OpenE8Calendar.Window(UUID.randomUUID(), 1,
            OffsetDateTime.parse(from), OffsetDateTime.parse(until));
    }
}
