-- First-year exploratory research context. Never an admitted prior project year.
CREATE TABLE app_open_dy_first_year_context (
 assessment_id uuid PRIMARY KEY REFERENCES app_open_dy_assessment(id),
 credit_start date,
 exploratory_from date,
 exploratory_until_exclusive date,
 context_gate text,
 policy_version text NOT NULL CHECK (policy_version='FIRST_YEAR_EXPLORATORY@1'),
 CHECK ((credit_start IS NULL AND exploratory_from IS NULL AND exploratory_until_exclusive IS NULL AND context_gate IS NOT NULL)
 OR (credit_start IS NOT NULL AND exploratory_from IS NOT NULL AND exploratory_until_exclusive=credit_start
     AND exploratory_from<credit_start AND context_gate IS NULL))
);
CREATE TRIGGER app_open_dy_first_year_context_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_first_year_context
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
