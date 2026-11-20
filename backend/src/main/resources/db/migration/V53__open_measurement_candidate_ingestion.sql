-- CF-OPEN-009 / #98, second slice: protected evidence + immutable measurement candidates.
-- The generic project evidence blob is reusable; no second raw ED/EC pipeline is
-- introduced. These rows are NOT admitted meter observations, E8 input or VCU.
CREATE TABLE app_project_evidence_blob (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 mime_type text NOT NULL CHECK (mime_type='text/csv'),
 file_name text NOT NULL CHECK (length(file_name) BETWEEN 5 AND 120),
 sha256 bytea NOT NULL CHECK (octet_length(sha256)=32),
 nonce bytea NOT NULL CHECK (octet_length(nonce)=12),
 encrypted bytea NOT NULL CHECK (octet_length(encrypted) BETWEEN 17 AND 2000016),
 size_bytes integer NOT NULL CHECK (size_bytes BETWEEN 1 AND 2000000),
 uploaded_by uuid NOT NULL REFERENCES app_identity(id),
 uploaded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,sha256),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment)
);
CREATE TABLE app_open_measurement_batch (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 evidence_blob_id uuid NOT NULL,
 source_system text NOT NULL CHECK (source_system ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
 parser_version text NOT NULL CHECK (parser_version='CF-OPEN-009-CSV-v1'),
 row_count integer NOT NULL CHECK (row_count BETWEEN 1 AND 2000),
 status text NOT NULL CHECK (status IN ('CANDIDATE','QUARANTINED')),
 submitted_by uuid NOT NULL REFERENCES app_identity(id),
 submitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,source_system,evidence_blob_id),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,evidence_blob_id)
   REFERENCES app_project_evidence_blob(project_id,id)
);
CREATE TABLE app_open_measurement_candidate (
 project_id text NOT NULL,
 batch_id uuid NOT NULL,
 source_line integer NOT NULL CHECK (source_line BETWEEN 2 AND 2001),
 source_sequence text CHECK (source_sequence ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
 meter_code text,
 meter_version integer,
 energy_kind text CHECK (energy_kind IN ('ED','EC')),
 start_at timestamptz,
 end_at timestamptz,
 source_zone text CHECK (source_zone IN ('UTC','America/Sao_Paulo')),
 kwh numeric(30,12) CHECK(kwh >= 0),
 source_line_sha256 bytea NOT NULL CHECK (octet_length(source_line_sha256)=32),
 state text NOT NULL CHECK (state IN ('CANDIDATE','QUARANTINED')),
 issue_codes text[] NOT NULL DEFAULT '{}'::text[] CHECK(cardinality(issue_codes) <= 20),
 PRIMARY KEY(project_id,batch_id,source_line),
 FOREIGN KEY(project_id,batch_id) REFERENCES app_open_measurement_batch(project_id,id),
 FOREIGN KEY(project_id,meter_code,meter_version)
   REFERENCES app_open_meter_configuration_revision(project_id,meter_code,version),
 CHECK((state='CANDIDATE' AND cardinality(issue_codes)=0
   AND source_sequence IS NOT NULL AND meter_code IS NOT NULL
   AND meter_version IS NOT NULL AND energy_kind IS NOT NULL
   AND start_at IS NOT NULL AND end_at IS NOT NULL AND end_at>start_at
   AND source_zone IS NOT NULL AND kwh IS NOT NULL)
   OR (state='QUARANTINED' AND cardinality(issue_codes)>0))
);
-- A dedup conflict across source+meter+period+sequence is a quarantine event
-- at the service layer, not a destructive update of prior evidence.
CREATE INDEX app_open_measurement_canonical_identity
 ON app_open_measurement_candidate(project_id,meter_code,start_at,end_at,source_sequence)
 WHERE state='CANDIDATE';
CREATE INDEX app_open_measurement_scope
 ON app_open_measurement_batch(project_id,submitted_at DESC);
CREATE TABLE app_open_measurement_upload_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 batch_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,batch_id) REFERENCES app_open_measurement_batch(project_id,id)
);

-- Preserve originals and parsed candidates even after failed QA, for replay
-- and review. Corrections must be new batches; no silent edits.
CREATE TRIGGER app_open_measurement_blob_immutable
 BEFORE UPDATE OR DELETE ON app_project_evidence_blob
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_measurement_batch_immutable
 BEFORE UPDATE OR DELETE ON app_open_measurement_batch
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_measurement_candidate_immutable
 BEFORE UPDATE OR DELETE ON app_open_measurement_candidate
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

-- OP is operational data entry only, not a preparer nor reviewer.
ALTER TABLE app_project_collaborator_grant
 DROP CONSTRAINT app_project_collaborator_grant_role_check;
ALTER TABLE app_project_collaborator_grant
 ADD CONSTRAINT app_project_collaborator_grant_role_check
 CHECK(role IN ('PREP','REV','OP'));

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK (action IN (
    'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
    'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
    'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
    'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
    'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
    'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
    'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
    'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
    'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION',
    'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING','SUBMIT_OPEN_MEASUREMENTS'
 ));
