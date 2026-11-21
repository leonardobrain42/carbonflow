-- CF-ECA-005: a contract or disclosure file is received for review.
-- The state check has no admitted-proof value.
CREATE TABLE app_project_rights_file (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 subject text NOT NULL CHECK (subject IN ('CONTRACT','DISCLOSURE')),
 subject_code text NOT NULL,
 evidence_blob_id uuid NOT NULL,
 file_name text NOT NULL,
 state text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (state = 'PENDING_REVIEW'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (usable_for_e8 = false),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment),
 FOREIGN KEY (project_id, evidence_blob_id)
   REFERENCES app_project_evidence_blob(project_id, id)
);
CREATE TABLE app_project_rights_file_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL,
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
 file_id uuid NOT NULL REFERENCES app_project_rights_file(id),
 PRIMARY KEY (actor_id, command_key)
);
CREATE TRIGGER app_project_rights_file_immutable
 BEFORE UPDATE OR DELETE ON app_project_rights_file
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_open_applicability_revision
 ADD COLUMN contract_file_id uuid,
 ADD COLUMN disclosure_file_id uuid;
