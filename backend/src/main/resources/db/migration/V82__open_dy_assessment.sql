-- #96 / P0.3: preparatory, immutable Dy assessment. No E8 admission.
CREATE TABLE app_open_dy_assessment (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 project_year integer NOT NULL CHECK (project_year BETWEEN 1 AND 100),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 triage_version integer NOT NULL CHECK (triage_version >= 0),
 prior_start timestamptz,
 prior_end_exclusive timestamptz,
 calendar_gate text,
 outcome text NOT NULL CHECK (outcome IN ('INCONCLUSIVE','FLEET_CREDITS_FOUND','NO_FLEET_CREDITS_DECLARED')),
 erc numeric(30,12),
 erf numeric(30,12),
 dy_preview numeric,
 programs text NOT NULL CHECK (length(btrim(programs)) BETWEEN 3 AND 1000),
 project_references text NOT NULL CHECK (length(btrim(project_references)) BETWEEN 3 AND 2000),
 evidence_locator text NOT NULL CHECK (length(btrim(evidence_locator)) BETWEEN 3 AND 1000),
 coverage_note text NOT NULL CHECK (length(btrim(coverage_note)) BETWEEN 8 AND 2000),
 assessment_sha256 bytea NOT NULL CHECK (octet_length(assessment_sha256)=32),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 command_sha256 bytea NOT NULL CHECK (octet_length(command_sha256)=32),
 UNIQUE (project_id, project_year, version),
 UNIQUE (author_id, command_key),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment),
 CHECK ((prior_start IS NULL AND prior_end_exclusive IS NULL AND calendar_gate IS NOT NULL)
     OR (prior_start IS NOT NULL AND prior_end_exclusive IS NOT NULL
         AND prior_end_exclusive>prior_start AND calendar_gate IS NULL AND project_year>1)),
 CHECK ((outcome='INCONCLUSIVE' AND erc IS NULL AND erf IS NULL AND dy_preview IS NULL)
     OR (outcome='FLEET_CREDITS_FOUND' AND calendar_gate IS NULL
         AND erc IS NOT NULL AND erc>=0 AND erf IS NOT NULL AND erf>0
         AND dy_preview IS NOT NULL AND dy_preview>=0 AND dy_preview<1)
     OR (outcome='NO_FLEET_CREDITS_DECLARED' AND calendar_gate IS NULL
         AND erc IS NOT NULL AND erc>=0 AND erf IS NOT NULL AND erf=0
         AND dy_preview IS NOT NULL AND dy_preview=1))
);
CREATE TRIGGER app_open_dy_assessment_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_assessment
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
