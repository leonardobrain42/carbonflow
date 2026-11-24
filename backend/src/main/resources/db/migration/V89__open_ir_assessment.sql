-- #96: preparatory IR assessment. Canonical references only, no licensed numbers.
CREATE TABLE app_open_ir_assessment (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 project_year integer NOT NULL CHECK (project_year BETWEEN 1 AND 100),
 source_year integer NOT NULL CHECK (source_year BETWEEN 1900 AND 2100),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 triage_version integer NOT NULL CHECK (triage_version>=0),
 period_code text NOT NULL,
 fleet_selection_id uuid NOT NULL,
 fuel_pin_id uuid NOT NULL,
 timing_sha256 bytea NOT NULL CHECK (octet_length(timing_sha256)=32),
 fleet_sha256 bytea NOT NULL CHECK (octet_length(fleet_sha256)=32),
 fuel_sha256 bytea NOT NULL CHECK (octet_length(fuel_sha256)=32),
 frequency text NOT NULL CHECK (frequency IN ('ANNUAL','NON_ANNUAL','UNKNOWN')),
 basis text NOT NULL CHECK (basis IN ('PROJECT_SPECIFIC','DEFAULT_VALUES','UNKNOWN')),
 rationale text NOT NULL CHECK (length(btrim(rationale)) BETWEEN 15 AND 2500),
 policy text NOT NULL CHECK (policy='OPEN-IR-ASSESSMENT@1'),
 kernel_sha256 bytea CHECK (octet_length(kernel_sha256)=32),
 assessment_sha256 bytea NOT NULL CHECK (octet_length(assessment_sha256)=32),
 state text NOT NULL CHECK (state IN ('PENDING_METHOD','PENDING_REVIEW')),
 candidate numeric,
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (NOT usable_for_e8),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 command_sha256 bytea NOT NULL CHECK (octet_length(command_sha256)=32),
 UNIQUE(project_id,project_year,source_year,version),
 UNIQUE(author_id,command_key),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,fleet_selection_id)
   REFERENCES app_open_e8_fleet_selection(project_id,id),
 FOREIGN KEY(project_id,fuel_pin_id)
   REFERENCES app_open_project_parameter_binding(project_id,id),
 CHECK ((state='PENDING_METHOD' AND candidate IS NULL AND kernel_sha256 IS NULL
           AND (frequency<>'ANNUAL' OR basis<>'PROJECT_SPECIFIC'))
     OR (state='PENDING_REVIEW' AND candidate IS NOT NULL AND candidate=1
           AND kernel_sha256 IS NOT NULL AND frequency='ANNUAL' AND basis='PROJECT_SPECIFIC'))
);
CREATE TRIGGER app_open_ir_assessment_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_assessment
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
