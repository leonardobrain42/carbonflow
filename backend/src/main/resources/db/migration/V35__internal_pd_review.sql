CREATE TABLE app_pd_reviewer_competence (
    project_id text NOT NULL REFERENCES app_project(id),
    section_id text NOT NULL REFERENCES app_technical_guide(id),
    reviewer_id uuid NOT NULL REFERENCES app_identity(id),
    verified_by uuid NOT NULL REFERENCES app_identity(id),
    reference text NOT NULL,
    valid_until timestamptz NOT NULL,
    version integer NOT NULL CHECK(version>0),
    CHECK(reviewer_id <> verified_by),
    PRIMARY KEY(project_id,section_id,reviewer_id,version)
);
CREATE TABLE app_pd_review_event (
    project_id text NOT NULL,
    section_id text NOT NULL,
    version integer NOT NULL,
    seq integer NOT NULL CHECK(seq>0),
    state text NOT NULL CHECK(state IN ('SUBMITTED','RETURNED','APPROVED_INTERNAL','FROZEN_INTERNAL','REOPENED')),
    reason text NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    decided_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    competence_version integer,
    PRIMARY KEY(project_id,section_id,version,seq),
    FOREIGN KEY(project_id,section_id,version) REFERENCES app_pd_section_version(project_id,section_id,version),
    FOREIGN KEY(project_id,section_id,actor_id,competence_version) REFERENCES app_pd_reviewer_competence(project_id,section_id,reviewer_id,version)
);
ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER',
      'VIEW_PD_REVIEW','DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS','SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION'
    ));
