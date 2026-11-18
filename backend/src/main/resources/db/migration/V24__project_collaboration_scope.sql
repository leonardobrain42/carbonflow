CREATE TABLE app_project_collaborator_grant (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    identity_id uuid NOT NULL REFERENCES app_identity(id),
    granted_by uuid NOT NULL REFERENCES app_identity(id),
    role text NOT NULL CHECK (role IN ('PREP','REV')),
    activities text[] NOT NULL CHECK (cardinality(activities) BETWEEN 1 AND 2
        AND activities <@ ARRAY['INTAKE','DOCUMENTATION']::text[]),
    valid_from timestamptz NOT NULL DEFAULT statement_timestamp(),
    valid_until timestamptz NOT NULL,
    revoked_at timestamptz,
    revoked_by uuid REFERENCES app_identity(id),
    CHECK (valid_until > valid_from),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),
    CHECK (granted_by <> identity_id),
    UNIQUE (project_id, id),
    FOREIGN KEY (project_id, organization_id, environment)
      REFERENCES app_project(id, organization_id, environment)
);

CREATE INDEX app_project_collaborator_grant_lookup
    ON app_project_collaborator_grant(project_id, identity_id) WHERE revoked_at IS NULL;

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER'
    ));
