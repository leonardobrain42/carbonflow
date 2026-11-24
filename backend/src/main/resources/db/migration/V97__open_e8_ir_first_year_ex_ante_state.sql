-- Q04 option B: a first-year rehearsal may carry the IR context state FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED.
-- Additive: both constraints only widen the allowed states; V92 rows remain valid.
ALTER TABLE app_open_e8_run
 DROP CONSTRAINT app_open_e8_run_reviewed_pins,
 DROP CONSTRAINT app_open_e8_ir_context_shape,
 ADD CONSTRAINT app_open_e8_ir_context_shape CHECK (
   (ir_context_sha256 IS NULL AND ir_context_state IS NULL AND ir_assessment_id IS NULL
     AND ir_assessment_sha256 IS NULL AND ir_assessment_version IS NULL)
   OR (ir_context_sha256 IS NOT NULL AND octet_length(ir_context_sha256)=32
     AND ir_context_state IS NOT NULL AND ir_context_state IN
     ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR',
      'STALE','PERIOD_REFERENCE_MISMATCH','REVIEW_REQUIRED','ANNUAL_EVIDENCE_RECHECK_REQUIRED',
      'CHECKED_INTERNAL_NOT_ADMITTED','FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED')
     AND ir_pin_id IS NULL
     AND ((ir_assessment_id IS NULL AND ir_assessment_sha256 IS NULL AND ir_assessment_version IS NULL
           AND ir_context_state IN ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR'))
       OR (ir_assessment_id IS NOT NULL AND ir_assessment_sha256 IS NOT NULL
           AND octet_length(ir_assessment_sha256)=32 AND ir_assessment_version IS NOT NULL
           AND ir_assessment_version>0)))),
 ADD CONSTRAINT app_open_e8_run_reviewed_pins CHECK (
   state<>'REVIEWED_INTERNAL' OR
   (fuel_pin_id IS NOT NULL AND dy_pin_id IS NOT NULL AND
    ((ir_context_sha256 IS NULL AND ir_pin_id IS NOT NULL)
     OR (ir_context_sha256 IS NOT NULL AND ir_assessment_id IS NOT NULL
       AND ir_context_state IN ('CHECKED_INTERNAL_NOT_ADMITTED','FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED')))));
