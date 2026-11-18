CREATE TABLE app_project_authority_request_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    request_id uuid NOT NULL,
    version integer NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    requested_power text NOT NULL CHECK (requested_power IN ('REPRESENT_PROJECT','SEND_INVITATIONS')),
    representation_basis text NOT NULL CHECK (char_length(btrim(representation_basis)) BETWEEN 1 AND 400),
    evidence_reference text NOT NULL CHECK (char_length(btrim(evidence_reference)) BETWEEN 1 AND 240),
    state text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK ((version = 1 AND state = 'SUBMITTED') OR (version = 2 AND state = 'WITHDRAWN')),
    PRIMARY KEY (project_id, request_id, version),
    FOREIGN KEY (project_id, organization_id, environment)
       REFERENCES app_project(id, organization_id, environment)
);

ALTER TABLE app_project_technical_access_event
    DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
    ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST'
    ));
