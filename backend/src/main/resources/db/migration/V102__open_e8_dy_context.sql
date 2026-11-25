-- #96/#97 (D14): new attempts resolve Dy from a versioned, independently reviewed assessment, not the legacy scalar pin.
-- Additive. Old rows keep dy_pin_id and a NULL context and remain valid; nothing here admits a value.
ALTER TABLE app_open_dy_assessment ADD CONSTRAINT app_open_dy_run_target
 UNIQUE(project_id,id,assessment_sha256,version);
ALTER TABLE app_open_e8_run
 ADD COLUMN dy_assessment_id uuid,
 ADD COLUMN dy_assessment_sha256 bytea,
 ADD COLUMN dy_assessment_version integer,
 ADD COLUMN dy_context_sha256 bytea,
 ADD COLUMN dy_context_state text,
 ADD CONSTRAINT app_open_e8_dy_assessment_fk
   FOREIGN KEY(project_id,dy_assessment_id,dy_assessment_sha256,dy_assessment_version)
   REFERENCES app_open_dy_assessment(project_id,id,assessment_sha256,version),
 ADD CONSTRAINT app_open_e8_dy_context_shape CHECK (
   (dy_context_sha256 IS NULL AND dy_context_state IS NULL AND dy_assessment_id IS NULL
     AND dy_assessment_sha256 IS NULL AND dy_assessment_version IS NULL)
   OR (dy_context_sha256 IS NOT NULL AND octet_length(dy_context_sha256)=32
     AND dy_context_state IS NOT NULL AND dy_context_state IN
     ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR','STALE',
      'ALLOCATION_BLOCKED','REVIEW_REQUIRED','CHECKED_INTERNAL_NOT_ADMITTED',
      'FIRST_YEAR_ABSENCE_CHECKED_NOT_ADMITTED')
     AND dy_pin_id IS NULL
     AND ((dy_assessment_id IS NULL AND dy_assessment_sha256 IS NULL AND dy_assessment_version IS NULL
           AND dy_context_state IN ('TIMING_UNRESOLVED','FIRST_YEAR_REVIEW_REQUIRED','MISSING_FOR_PROJECT_YEAR'))
       OR (dy_assessment_id IS NOT NULL AND dy_assessment_sha256 IS NOT NULL
           AND octet_length(dy_assessment_sha256)=32 AND dy_assessment_version IS NOT NULL
           AND dy_assessment_version>0)))),
 DROP CONSTRAINT app_open_e8_run_reviewed_pins,
 ADD CONSTRAINT app_open_e8_run_reviewed_pins CHECK (
   state<>'REVIEWED_INTERNAL' OR
   (fuel_pin_id IS NOT NULL AND
    ((dy_context_sha256 IS NULL AND dy_pin_id IS NOT NULL)
     OR (dy_context_sha256 IS NOT NULL AND dy_assessment_id IS NOT NULL
       AND dy_context_state IN ('CHECKED_INTERNAL_NOT_ADMITTED','FIRST_YEAR_ABSENCE_CHECKED_NOT_ADMITTED'))) AND
    ((ir_context_sha256 IS NULL AND ir_pin_id IS NOT NULL)
     OR (ir_context_sha256 IS NOT NULL AND ir_assessment_id IS NOT NULL
       AND ir_context_state IN ('CHECKED_INTERNAL_NOT_ADMITTED','FIRST_YEAR_EX_ANTE_CHECKED_NOT_ADMITTED')))));
