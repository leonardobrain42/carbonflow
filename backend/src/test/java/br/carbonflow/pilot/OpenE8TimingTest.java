package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.time.LocalDate;
import java.time.OffsetDateTime;

import org.junit.jupiter.api.Test;

class OpenE8TimingTest {
    private static final LocalDate START = LocalDate.of(2027, 2, 15);

    @Test
    void keepsTheProjectYearAcrossWindowsAndCivilYears() {
        assertEquals(new OpenE8Timing.Year(1, 2027, null),
            OpenE8Timing.years(START, window("2027-03-12T00:00Z", "2027-03-12T01:00Z")));
        assertEquals(new OpenE8Timing.Year(1, 2028, null),
            OpenE8Timing.years(START, window("2028-01-01T03:00Z", "2028-01-01T04:00Z")));
        assertEquals(new OpenE8Timing.Year(2, 2028, null),
            OpenE8Timing.years(START, window("2028-02-15T03:00Z", "2028-02-15T04:00Z")));
    }

    @Test
    void handlesTheExclusiveLimitAtTheBrazilianAnniversary() {
        assertEquals(new OpenE8Timing.Year(1, 2028, null),
            OpenE8Timing.years(START, window("2028-02-15T02:00Z", "2028-02-15T03:00Z")));
        assertEquals("WINDOW_PROJECT_YEAR_BOUNDARY",
            OpenE8Timing.years(START, window("2028-02-15T02:00Z", "2028-02-15T04:00Z")).gate());
        assertEquals("WINDOW_BEFORE_CREDIT_START",
            OpenE8Timing.years(START, window("2027-02-15T02:00Z", "2027-02-15T04:00Z")).gate());
    }

    @Test
    void resolvesUtcForAnnualSourcesAndHandlesLeapDayAnniversaries() {
        assertEquals("WINDOW_SOURCE_YEAR_BOUNDARY",
            OpenE8Timing.years(START, window("2027-12-31T23:00Z", "2028-01-01T01:00Z")).gate());
        assertEquals(new OpenE8Timing.Year(2, 2029, null),
            OpenE8Timing.years(LocalDate.of(2028, 2, 29),
                window("2029-02-28T03:00Z", "2029-02-28T04:00Z")));
    }

    private static OpenE8Timing.Window window(String start, String end) {
        return new OpenE8Timing.Window(OffsetDateTime.parse(start), OffsetDateTime.parse(end));
    }
}
