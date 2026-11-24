package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

class OpenE8AnnualCalendarTest {
    @Test
    void usesBrazilianAnniversariesIncludingLeapYearsAndTheirSharedBoundary() {
        var start = LocalDate.of(2023, 3, 1);
        var first = OpenE8Timing.yearWindow(start, 1);
        var second = OpenE8Timing.yearWindow(start, 2);
        assertEquals("2023-03-01T03:00:00Z", first.from().toInstant().toString());
        assertEquals(366, Duration.between(first.from(), first.until()).toDays());
        assertEquals(first.until().toInstant(), second.from().toInstant());
        assertEquals(365, Duration.between(second.from(), second.until()).toDays());
        var leapStart = LocalDate.of(2024, 2, 29);
        assertEquals(LocalDate.of(2025, 2, 28), OpenE8Timing.yearWindow(leapStart, 1).until().toLocalDate());
        assertEquals(OpenE8Timing.yearWindow(leapStart, 1).until(), OpenE8Timing.yearWindow(leapStart, 2).from());
        assertEquals(LocalDate.of(2028, 2, 29), OpenE8Timing.yearWindow(leapStart, 4).until().toLocalDate());
    }

    @Test
    void rejectsUnknownStartAndInvalidProjectYear() {
        assertThrows(IllegalArgumentException.class, () -> OpenE8Timing.yearWindow(null, 1));
        assertThrows(IllegalArgumentException.class, () -> OpenE8Timing.yearWindow(LocalDate.of(2027, 1, 1), 0));
        assertThrows(IllegalArgumentException.class, () -> OpenE8Timing.yearWindow(LocalDate.of(2027, 1, 1), 101));
    }
}
