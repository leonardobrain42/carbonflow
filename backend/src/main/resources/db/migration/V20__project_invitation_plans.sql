CREATE TABLE app_project_invitation_plan_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    plan_id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    email_normalized text NOT NULL CHECK (email_normalized = lower(btrim(email_normalized))
        AND char_length(email_normalized) BETWEEN 3 AND 254),
    proposed_role text NOT NULL CHECK (proposed_role IN ('PREP', 'REV')),
    activities text[] NOT NULL CHECK (cardinality(activities) BETWEEN 1 AND 7
        AND activities <@ ARRAY['INTAKE','EVIDENCE','BASELINE','ADDITIONALITY','CALCULATION','MONITORING','DOCUMENTATION']::text[]),
    deadline date,
    competence text CHECK (char_length(competence) <= 400),
    declared_conflict text NOT NULL CHECK (declared_conflict IN ('UNKNOWN','NONE_DECLARED','DECLARED')),
    conflict_note text CHECK (char_length(conflict_note) <= 400),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (project_id, plan_id, version),
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
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN'
    ));
