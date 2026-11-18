CREATE TABLE app_project_invitation (
    id uuid PRIMARY KEY,
    project_id text NOT NULL REFERENCES app_project(id),
    project_title text NOT NULL,
    plan_id uuid NOT NULL,
    plan_version integer NOT NULL,
    sender_id uuid NOT NULL REFERENCES app_identity(id),
    authority_request_id uuid NOT NULL,
    authority_version integer NOT NULL,
    command_key text NOT NULL,
    sent_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    accept_until timestamptz NOT NULL,
    access_until timestamptz NOT NULL,
    accepted_at timestamptz,
    grant_id uuid UNIQUE,
    revoked_at timestamptz,
    revoked_by uuid REFERENCES app_identity(id),
    CHECK (sent_at < accept_until AND accept_until <= access_until),
    CHECK ((accepted_at IS NULL) = (grant_id IS NULL)),
    CHECK ((revoked_at IS NULL) = (revoked_by IS NULL)),
    UNIQUE (project_id, sender_id, command_key),
    FOREIGN KEY (project_id, plan_id, plan_version)
      REFERENCES app_project_invitation_plan_revision(project_id, plan_id, version),
    FOREIGN KEY (project_id, authority_request_id, authority_version)
      REFERENCES app_compliance_power_decision(project_id, request_id, version),
    FOREIGN KEY (project_id, grant_id)
      REFERENCES app_project_collaborator_grant(project_id, id)
);

CREATE INDEX app_project_invitation_plan ON app_project_invitation(project_id, plan_id);
CREATE INDEX app_project_invitation_recipient ON app_project_invitation_plan_revision(email_normalized);

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER',
      'VIEW_ACTIVE_INVITATIONS','SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION'
    ));
