CREATE TABLE app_technical_guide (
    id text PRIMARY KEY,
    methodology text NOT NULL,
    position integer NOT NULL,
    title text NOT NULL,
    activity text NOT NULL,
    guidance text NOT NULL,
    fields jsonb NOT NULL
);
CREATE TABLE app_technical_guide_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL,
    guide_id text NOT NULL REFERENCES app_technical_guide(id),
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    saved_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    answers jsonb NOT NULL,
    PRIMARY KEY(project_id,guide_id,version),
    FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment)
);
ALTER TABLE app_project_collaborator_grant DROP CONSTRAINT app_project_collaborator_grant_activities_check;
ALTER TABLE app_project_collaborator_grant ADD CHECK (cardinality(activities) BETWEEN 1 AND 6
    AND activities <@ ARRAY['INTAKE','DOCUMENTATION','BASELINE','ADDITIONALITY','CALCULATION','MONITORING']::text[]);

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER',
      'VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS','SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION'
    ));
