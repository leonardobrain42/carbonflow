-- Q05 (24/11/2026): project year 1 may record NO_FLEET_CREDITS_DECLARED, only through the exploratory
-- window and a resolved first-year context. Additive: the old outcome rule is widened, never narrowed.
DO $$
DECLARE old_name text;
BEGIN
  SELECT conname INTO old_name FROM pg_constraint
   WHERE conrelid='app_open_dy_assessment'::regclass AND contype='c'
     AND pg_get_constraintdef(oid) LIKE '%FLEET_CREDITS_FOUND%'
     AND pg_get_constraintdef(oid) LIKE '%dy_preview%';
  IF old_name IS NULL THEN RAISE EXCEPTION 'Dy outcome constraint not found'; END IF;
  EXECUTE format('ALTER TABLE app_open_dy_assessment DROP CONSTRAINT %I',old_name);
END $$;

ALTER TABLE app_open_dy_assessment ADD CONSTRAINT app_open_dy_outcome_shape CHECK (
  (outcome='INCONCLUSIVE' AND erc IS NULL AND erf IS NULL AND dy_preview IS NULL)
  OR (outcome='FLEET_CREDITS_FOUND' AND calendar_gate IS NULL
      AND erc IS NOT NULL AND erc>=0 AND erf IS NOT NULL AND erf>0
      AND dy_preview IS NOT NULL AND dy_preview>=0 AND dy_preview<1)
  OR (outcome='NO_FLEET_CREDITS_DECLARED' AND calendar_gate IS NULL
      AND erc IS NOT NULL AND erc>=0 AND erf IS NOT NULL AND erf=0
      AND dy_preview IS NOT NULL AND dy_preview=1)
  OR (outcome='NO_FLEET_CREDITS_DECLARED' AND project_year=1
      AND calendar_gate='FIRST_YEAR_EXPLORATORY_WINDOW'
      AND prior_start IS NULL AND prior_end_exclusive IS NULL
      AND erc IS NOT NULL AND erc=0 AND erf IS NOT NULL AND erf=0
      AND dy_preview IS NOT NULL AND dy_preview=1));

-- A first-year absence is only valid with a resolved exploratory context, checked at commit because the
-- context row is inserted after the assessment row.
CREATE FUNCTION app_open_dy_first_year_absence_guard() RETURNS trigger AS $$
BEGIN
  IF NEW.calendar_gate='FIRST_YEAR_EXPLORATORY_WINDOW' AND NOT EXISTS (
       SELECT 1 FROM app_open_dy_first_year_context x
        WHERE x.assessment_id=NEW.id AND x.context_gate IS NULL) THEN
    RAISE EXCEPTION 'First-year absence requires a resolved exploratory context';
  END IF;
  RETURN NULL;
END $$ LANGUAGE plpgsql;
CREATE CONSTRAINT TRIGGER app_open_dy_first_year_absence_guard
  AFTER INSERT ON app_open_dy_assessment
  DEFERRABLE INITIALLY DEFERRED
  FOR EACH ROW EXECUTE FUNCTION app_open_dy_first_year_absence_guard();
