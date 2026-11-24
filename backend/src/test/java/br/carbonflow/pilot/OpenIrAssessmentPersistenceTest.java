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
    void annualEvidenceMigrationHasValidatedForeignKeysAndImmutableTrigger() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='91' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals("O",database.read(c->first(c,"""
            SELECT tgenabled::text FROM pg_trigger
            WHERE tgrelid='app_open_ir_annual_evidence'::regclass
                AND tgname='app_open_ir_annual_evidence_immutable'
            """,r->r.getString(1))));
        assertEquals(3,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_ir_annual_evidence'::regclass AND contype='f' AND convalidated
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void runContextMigrationPreservesValidatedReferenceAndGateConstraints() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='92' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(3,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_e8_run'::regclass AND convalidated
                AND conname IN ('app_open_e8_ir_assessment_fk','app_open_e8_ir_context_shape',
                    'app_open_e8_run_reviewed_pins')
            """,r->r.getInt(1))).intValue());
        assertEquals(0,database.read(c->first(c,"""
            SELECT count(*)::int FROM app_open_e8_run
            WHERE ir_context_sha256 IS NOT NULL AND ir_pin_id IS NOT NULL
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void initialPlanMigrationPreservesImmutableReviewAndScopeGuard() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='93' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(2,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_ir_review'::regclass AND convalidated
              AND conname IN ('app_open_ir_initial_plan_shape','app_open_ir_review_outcome_check')
            """,r->r.getInt(1))).intValue());
        assertEquals(2,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_trigger WHERE tgrelid='app_open_ir_review'::regclass
              AND tgenabled='O' AND tgname IN ('app_open_ir_review_immutable','app_open_ir_initial_review_scope')
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void exAnteHandoffHasValidatedCompositeForeignKeyAndInsertGuard() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='95' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(2,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_ir_review'::regclass AND convalidated
              AND conname IN ('app_open_ir_ex_ante_review_fk','app_open_ir_ex_ante_review_shape')
            """,r->r.getInt(1))).intValue());
        assertEquals("O",database.read(c->first(c,"""
            SELECT tgenabled::text FROM pg_trigger WHERE tgrelid='app_open_ir_review'::regclass
              AND tgname='app_open_ir_ex_ante_review_scope'
            """,r->r.getString(1))));
    }

    @Test
    void firstYearExAnteMigrationWidensStatesWithoutDroppingValidatedGuards() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='97' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(2,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_e8_run'::regclass AND convalidated
                AND conname IN ('app_open_e8_ir_context_shape','app_open_e8_run_reviewed_pins')
                AND pg_get_constraintdef(oid) LIKE '%FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED%'
            """,r->r.getInt(1))).intValue());
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_e8_run'::regclass AND convalidated AND conname='app_open_e8_ir_assessment_fk'
            """,r->r.getInt(1))).intValue());
        assertEquals(0,database.read(c->first(c,"""
            SELECT count(*)::int FROM app_open_e8_run
            WHERE ir_context_state='FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED' AND ir_assessment_id IS NULL
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void dyFirstYearAbsenceMigrationWidensOutcomeShapeAndGuardsTheContextAtCommit() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='98' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_dy_assessment'::regclass AND convalidated AND conname='app_open_dy_outcome_shape'
                AND pg_get_constraintdef(oid) LIKE '%FIRST_YEAR_EXPLORATORY_WINDOW%'
            """,r->r.getInt(1))).intValue());
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_dy_assessment'::regclass AND contype='c'
                AND pg_get_constraintdef(oid) LIKE '%FLEET_CREDITS_FOUND%' AND pg_get_constraintdef(oid) LIKE '%dy_preview%'
            """,r->r.getInt(1))).intValue(),"exactly one outcome-shape constraint remains");
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_trigger
            WHERE tgrelid='app_open_dy_assessment'::regclass AND tgname='app_open_dy_first_year_absence_guard'
                AND tgenabled='O' AND tgdeferrable AND tginitdeferred
            """,r->r.getInt(1))).intValue());
        assertEquals(0,database.read(c->first(c,"""
            SELECT count(*)::int FROM app_open_dy_assessment a
            WHERE a.calendar_gate='FIRST_YEAR_EXPLORATORY_WINDOW' AND NOT EXISTS (
              SELECT 1 FROM app_open_dy_first_year_context x WHERE x.assessment_id=a.id AND x.context_gate IS NULL)
            """,r->r.getInt(1))).intValue());
    }

    @Test
    void temporalEvidenceMigrationHasValidatedConstraintsAndImmutableTrigger() {
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM flyway_schema_history WHERE version='96' AND success
            """,r->r.getInt(1))).intValue());
        assertEquals(1,database.read(c->first(c,"""
            SELECT count(*)::int FROM pg_constraint
            WHERE conrelid='app_open_ir_annual_temporal_evidence'::regclass AND contype='f' AND convalidated
                AND confrelid='app_open_ir_annual_evidence'::regclass
            """,r->r.getInt(1))).intValue());
        assertEquals("O",database.read(c->first(c,"""
            SELECT tgenabled::text FROM pg_trigger WHERE tgrelid='app_open_ir_annual_temporal_evidence'::regclass
              AND tgname='app_open_ir_annual_temporal_immutable'
            """,r->r.getString(1))));
    }

    @Test
    void digestSeparatesTextBoundariesNullAndRevision() {
        assertNotEquals(OpenIrAssessments.digest("ab","c"),OpenIrAssessments.digest("a","bc"));
        assertNotEquals(OpenIrAssessments.digest("a",null),OpenIrAssessments.digest("a",""));
        assertNotEquals(OpenIrAssessments.digest("p",1),OpenIrAssessments.digest("p",2));
        assertEquals(OpenIrAssessments.digest("p",1),OpenIrAssessments.digest("p",1));
    }
}
