CREATE TABLE app_project_experience_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    mode text NOT NULL CHECK (mode IN ('GUIDED', 'TECHNICAL')),
    declared_profile text CHECK (declared_profile IN ('PROPONENT', 'TECHNICAL', 'OPERATIONS', 'OTHER')),
    declared_competence text CHECK (char_length(declared_competence) <= 400),
    proposed_pp text CHECK (char_length(proposed_pp) <= 120),
    proposed_prep text CHECK (char_length(proposed_prep) <= 120),
    proposed_rev text CHECK (char_length(proposed_rev) <= 120),
    proposed_op text CHECK (char_length(proposed_op) <= 120),
    proposed_vvb text CHECK (char_length(proposed_vvb) <= 120),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (project_id, version),
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
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE'
    ));
