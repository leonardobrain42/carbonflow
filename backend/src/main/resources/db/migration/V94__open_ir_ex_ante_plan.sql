-- #96: planned sources and procedures, not admitted baseline observations.
CREATE TABLE app_open_ir_ex_ante_plan (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 triage_version integer NOT NULL CHECK (triage_version>=0),
 intake_version integer NOT NULL CHECK (intake_version>=0),
 applicability_version integer NOT NULL CHECK (applicability_version>=0),
 context_sha256 bytea NOT NULL CHECK (octet_length(context_sha256)=32),
 plan jsonb NOT NULL CHECK (jsonb_typeof(plan)='object'
   AND plan ?& ARRAY['evr','afec','mpg','fuel','rationale']),
 plan_sha256 bytea NOT NULL CHECK (octet_length(plan_sha256)=32),
 policy text NOT NULL CHECK (policy='OPEN-IR-EX-ANTE@1'),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 command_sha256 bytea NOT NULL CHECK (octet_length(command_sha256)=32),
 UNIQUE(project_id,version),
 UNIQUE(author_id,command_key),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment)
);
CREATE TRIGGER app_open_ir_ex_ante_plan_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_ex_ante_plan
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
