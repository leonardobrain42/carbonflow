package br.carbonflow.pilot;

import static br.carbonflow.pilot.PilotSql.first;
import static org.junit.jupiter.api.Assertions.*;

import io.quarkus.test.junit.QuarkusTest;
import jakarta.inject.Inject;
import org.junit.jupiter.api.Test;

@QuarkusTest
class OpenIrAssessmentPersistenceTest {
    @Inject PilotDatabase database;

    @Test
    void additiveMigrationHasValidatedConstraintsAndImmutableTrigger() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='89' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals("O",database.read(c->first(c,"""
            SELECT tgenabled::text FROM pg_trigger
            WHERE tgrelid='app_open_ir_assessment'::regclass
                AND tgname='app_open_ir_assessment_immutable'
            """,r->r.getString(1))));
        assertEquals(3,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_ir_assessment'::regclass AND contype='f' AND convalidated
                AND confrelid IN ('app_project'::regclass,'app_open_e8_fleet_selection'::regclass,
                                  'app_open_project_parameter_binding'::regclass)
            """,r->r.getInt(1))).intValue());
        assertEquals(0,database.read(c->first(c,"""
            SELECT count(*)::int FROM app_open_ir_assessment WHERE usable_for_e8
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void digestSeparatesTextBoundariesNullAndRevision() {
        assertNotEquals(OpenIrAssessments.digest("ab","c"),OpenIrAssessments.digest("a","bc"));
        assertNotEquals(OpenIrAssessments.digest("a",null),OpenIrAssessments.digest("a",""));
        assertNotEquals(OpenIrAssessments.digest("p",1),OpenIrAssessments.digest("p",2));
        assertEquals(OpenIrAssessments.digest("p",1),OpenIrAssessments.digest("p",1));
    }
}
