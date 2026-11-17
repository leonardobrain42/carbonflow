-- CF-IMP-009: versioned UX wizard atop app_project, not a second E8 intake or a normative workflow engine.
CREATE TABLE app_project_triage_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    current_step integer NOT NULL CHECK (current_step BETWEEN 1 AND 5),
    answers jsonb NOT NULL CHECK (jsonb_typeof(answers) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (project_id, version),
    FOREIGN KEY (project_id, organization_id, environment)
       REFERENCES app_project(id, organization_id, environment)
);

CREATE TABLE app_project_triage_command (
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    action text NOT NULL CHECK (action IN ('SAVE_STEP','MOVE_CURSOR','APPLY_EXAMPLE')),
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    response jsonb NOT NULL CHECK (jsonb_typeof(response) = 'object'),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (actor_id, command_key),
    FOREIGN KEY (project_id, organization_id, environment)
       REFERENCES app_project(id, organization_id, environment)
);

ALTER TABLE app_project_technical_access_event
    DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
    ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE'
    ));

CREATE INDEX app_project_triage_revision_recent
    ON app_project_triage_revision(project_id, version DESC);
