-- #96: first-year baseline plan is not an observed annual update.
ALTER TABLE app_open_ir_review
 DROP CONSTRAINT app_open_ir_review_outcome_check,
 ADD CONSTRAINT app_open_ir_review_outcome_check CHECK
   (outcome IN ('CHECKED_INTERNAL','NEEDS_INFORMATION','REJECTED','INITIAL_BASELINE_CHECKED')),
 ADD COLUMN initial_evr_plan text,
 ADD COLUMN initial_afec_plan text,
 ADD COLUMN initial_mpg_plan text,
 ADD COLUMN initial_fuel_plan text,
 ADD COLUMN initial_followup_on date,
 ADD COLUMN initial_plan_sha256 bytea,
 ADD CONSTRAINT app_open_ir_initial_plan_shape CHECK (
   (outcome='INITIAL_BASELINE_CHECKED' AND annual_update_basis='PLAN_ONLY'
     AND initial_evr_plan IS NOT NULL AND length(btrim(initial_evr_plan)) BETWEEN 15 AND 2000
     AND initial_afec_plan IS NOT NULL AND length(btrim(initial_afec_plan)) BETWEEN 15 AND 2000
     AND initial_mpg_plan IS NOT NULL AND length(btrim(initial_mpg_plan)) BETWEEN 15 AND 2000
     AND initial_fuel_plan IS NOT NULL AND length(btrim(initial_fuel_plan)) BETWEEN 15 AND 2000
     AND initial_followup_on IS NOT NULL AND initial_plan_sha256 IS NOT NULL
     AND octet_length(initial_plan_sha256)=32)
   OR (outcome<>'INITIAL_BASELINE_CHECKED'
     AND initial_evr_plan IS NULL AND initial_afec_plan IS NULL
     AND initial_mpg_plan IS NULL AND initial_fuel_plan IS NULL
     AND initial_followup_on IS NULL AND initial_plan_sha256 IS NULL));

-- Enforce initial-year scope in the database as well as the service. Existing
-- composite FK still binds the exact assessment and segregated reviewer.
CREATE FUNCTION app_open_ir_initial_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.outcome='INITIAL_BASELINE_CHECKED' AND NOT EXISTS (
   SELECT 1 FROM app_open_ir_assessment a
   WHERE a.id=NEW.assessment_id AND a.project_year=1 AND a.state='PENDING_REVIEW'
     AND a.frequency='ANNUAL' AND a.basis='PROJECT_SPECIFIC'
 ) THEN
   RAISE EXCEPTION 'Initial baseline review requires first-year annual specific assessment';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER app_open_ir_initial_review_scope
 BEFORE INSERT ON app_open_ir_review
 FOR EACH ROW EXECUTE FUNCTION app_open_ir_initial_review_guard();
