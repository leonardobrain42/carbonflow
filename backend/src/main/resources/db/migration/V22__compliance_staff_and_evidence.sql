CREATE TABLE app_compliance_mfa (
    identity_id uuid PRIMARY KEY REFERENCES app_identity(id),
    last_counter bigint NOT NULL DEFAULT -1,
    window_start timestamptz NOT NULL,
    failures integer NOT NULL CHECK (failures BETWEEN 0 AND 5)
);

CREATE TABLE app_compliance_session (
    token_hash bytea PRIMARY KEY REFERENCES app_account_session(token_hash) ON DELETE CASCADE,
    credential_tag bytea NOT NULL CHECK (octet_length(credential_tag) = 32),
    expires_at timestamptz NOT NULL
);

CREATE TABLE app_compliance_access_event (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    project_id text REFERENCES app_project(id),
    action text NOT NULL CHECK (action IN ('MFA_VERIFIED','MFA_DENIED','VIEW_QUEUE','DOWNLOAD_DOCUMENT')),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE app_compliance_evidence (
    project_id text NOT NULL,
    request_id uuid NOT NULL,
    submission_version integer NOT NULL DEFAULT 1 CHECK (submission_version = 1),
    id uuid NOT NULL,
    uploaded_by uuid NOT NULL REFERENCES app_identity(id),
    sha256 bytea NOT NULL CHECK (octet_length(sha256) = 32),
    nonce bytea NOT NULL CHECK (octet_length(nonce) = 12),
    bytes_count integer NOT NULL CHECK (bytes_count BETWEEN 6 AND 2097152),
    encrypted bytea NOT NULL CHECK (octet_length(encrypted) = bytes_count + 16),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (project_id, request_id, id),
    FOREIGN KEY (project_id, request_id, submission_version)
      REFERENCES app_project_authority_request_revision(project_id, request_id, version)
);

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT'
    ));
