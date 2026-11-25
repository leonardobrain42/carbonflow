package br.carbonflow.pilot;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URL;

import com.fasterxml.jackson.databind.ObjectMapper;
import io.quarkus.test.common.QuarkusTestResource;
import io.quarkus.test.common.http.TestHTTPResource;
import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

/**
 * Proves that the shared OPEN E8 fixture can prepare several periods, in different source years, inside one
 * project. It checks the fixture only (every period reaches a staff fleet/rating hand-off that is still
 * PREPARATORY_NOT_ADMITTED); it asserts nothing about IR, reuse or calculation.
 */
@QuarkusTest
@QuarkusTestResource(value=OpenE8TestFixture.Settings.class,restrictToAnnotatedClass=true)
class OpenE8TestFixtureTest {
    @Inject PilotDatabase database;
    @Inject ObjectMapper mapper;
    @TestHTTPResource URL base;

    @Test
    void preparesPeriodsInDifferentSourceYearsOfTheSameProject() throws Exception {
        var fixture=new OpenE8TestFixture(database,mapper,base);
        var scenario=fixture.openProject("2026-01-15");
        var first=fixture.preparePeriod(scenario,"Y1-M1",2026,2);
        var second=fixture.preparePeriod(scenario,"Y2-M1",2027,2);
        var third=fixture.preparePeriod(scenario,"Y2-M2",2027,3);
        assertNotEquals(first.snapshotId(),second.snapshotId());
        assertNotEquals(second.fleetSelectionId(),third.fleetSelectionId());
        assertEquals(2026,first.year());
        assertEquals(2027,third.year());
        var readiness=fixture.json(fixture.call("GET","/v1/projects/"+scenario.project()
            +"/technical/open-calculation-readiness",scenario.owner().cookie(),null,null));
        assertFalse(readiness.path("canRunE8").asBoolean(),
            "A fixture period must never be calculable: nothing is admitted");
    }
}
