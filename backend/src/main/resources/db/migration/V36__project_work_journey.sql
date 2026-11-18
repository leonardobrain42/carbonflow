-- CF-IMP-014. The definition pins the product navigation and task IDs.
-- Operational states are projected from the same immutable revisions the editors use.
CREATE TABLE app_journey_definition (
    version integer PRIMARY KEY CHECK (version > 0)
);

INSERT INTO app_journey_definition VALUES (1);

CREATE TABLE app_journey_task_definition (
    definition_version integer NOT NULL REFERENCES app_journey_definition(version),
    task_id text NOT NULL,
    position integer NOT NULL,
    title text NOT NULL,
    kind text NOT NULL CHECK (kind IN ('TRIAGE','INTAKE','GUIDE','DOSSIER','SECTION','REVIEW')),
    guide_id text REFERENCES app_technical_guide(id),
    CHECK ((guide_id IS NOT NULL) = (kind IN ('GUIDE','SECTION','REVIEW'))),
    PRIMARY KEY(definition_version, task_id)
);

INSERT INTO app_journey_task_definition
    (definition_version, task_id, position, title, kind, guide_id)
VALUES
    (1,'triage',10,'Preencher triagem','TRIAGE',null),
    (1,'intake',20,'Preparar informações do projeto','INTAKE',null),
    (1,'dossier',100,'Preparar prévia do dossiê','DOSSIER',null);

INSERT INTO app_journey_task_definition
    (definition_version, task_id, position, title, kind, guide_id)
SELECT 1,'guide-'||id,position,title,'GUIDE',id
FROM app_technical_guide WHERE methodology='VM0038_V1_1'
UNION ALL
SELECT 1,'section-'||id,100+position,'Redigir · '||title,'SECTION',id
FROM app_technical_guide WHERE methodology='VM0038_V1_1'
UNION ALL
SELECT 1,'review-'||id,200+position,'Revisão · '||title,'REVIEW',id
FROM app_technical_guide WHERE methodology='VM0038_V1_1' AND activity <> 'OWNER';

CREATE TABLE app_project_journey_instance (
    project_id text PRIMARY KEY REFERENCES app_project(id),
    definition_version integer NOT NULL REFERENCES app_journey_definition(version)
);

INSERT INTO app_project_journey_instance(project_id, definition_version)
SELECT id,1 FROM app_project;

CREATE FUNCTION app_project_journey_start() RETURNS trigger
LANGUAGE plpgsql AS $$
BEGIN
    INSERT INTO app_project_journey_instance(project_id, definition_version) VALUES (NEW.id, 1);
    RETURN NEW;
END;
$$;

CREATE TRIGGER app_project_journey_start AFTER INSERT ON app_project
FOR EACH ROW EXECUTE FUNCTION app_project_journey_start();

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
    CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER',
      'VIEW_JOURNEY','VIEW_PD_REVIEW','DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS','SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION'
    ));
