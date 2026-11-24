-- #96/#97: new attempts resolve IR from a structured assessment, not a legacy scalar.
ALTER TABLE app_open_ir_assessment ADD CONSTRAINT app_open_ir_run_target
 UNIQUE(project_id,id,assessment_sha256,version);
ALTER TABLE app_open_e8_run
 ADD COLUMN ir_assessment_id uuid,
 ADD COLUMN ir_assessment_sha256 bytea,
 ADD COLUMN ir_assessment_version integer,
 ADD COLUMN ir_context_sha256 bytea,
 ADD COLUMN ir_context_state text,
 ADD CONSTRAINT app_open_e8_ir_assessment_fk
   FOREIGN KEY(project_id,ir_assessment_id,ir_assessment_sha256,ir_assessment_version)
   REFERENCES app_open_ir_assessment(project_id,id,assessment_sha256,version),
 ADD CONSTRAINT app_open_e8_ir_context_shape CHECK (
   (ir_context_sha256 IS NULL AND ir_context_state IS NULL AND ir_assessment_id IS NULL
     AND ir_assessment_sha256 IS NULL AND ir_assessment_version IS NULL)
   OR (ir_context_sha256 IS NOT NULL AND octet_length(ir_context_sha256)=32
     AND ir_context_state IS NOT NULL AND ir_context_state IN
     ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR',
      'STALE','PERIOD_REFERENCE_MISMATCH','REVIEW_REQUIRED','ANNUAL_EVIDENCE_RECHECK_REQUIRED',
      'CHECKED_INTERNAL_NOT_ADMITTED')
     AND ir_pin_id IS NULL
     AND ((ir_assessment_id IS NULL AND ir_assessment_sha256 IS NULL AND ir_assessment_version IS NULL
           AND ir_context_state IN ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR'))
       OR (ir_assessment_id IS NOT NULL AND ir_assessment_sha256 IS NOT NULL
           AND octet_length(ir_assessment_sha256)=32 AND ir_assessment_version IS NOT NULL
           AND ir_assessment_version>0)))),
 DROP CONSTRAINT app_open_e8_run_reviewed_pins,
 ADD CONSTRAINT app_open_e8_run_reviewed_pins CHECK (
   state<>'REVIEWED_INTERNAL' OR
   (fuel_pin_id IS NOT NULL AND dy_pin_id IS NOT NULL AND
    ((ir_context_sha256 IS NULL AND ir_pin_id IS NOT NULL)
     OR (ir_context_sha256 IS NOT NULL AND ir_assessment_id IS NOT NULL
       AND ir_context_state='CHECKED_INTERNAL_NOT_ADMITTED'))));
