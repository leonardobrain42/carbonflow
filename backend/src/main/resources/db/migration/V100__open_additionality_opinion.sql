-- #99: independent internal opinion per additionality criterion. Records a human opinion only:
-- it never concludes additionality, never admits a route and never feeds E8.
CREATE TABLE app_open_additionality_opinion (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 criterion text NOT NULL CHECK (criterion IN ('POSITIVE_LIST','REGULATORY_SURPLUS','CHRONOLOGY')),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK (outcome IN ('CHECKED_INTERNAL','RETURNED')),
 rationale text NOT NULL CHECK (char_length(rationale) BETWEEN 30 AND 2000),
 evidence_reference text NOT NULL CHECK (char_length(evidence_reference) BETWEEN 15 AND 600),
 context_sha256 bytea NOT NULL CHECK (octet_length(context_sha256)=32),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (project_id, criterion, version)
);
CREATE TRIGGER app_open_additionality_opinion_immutable
 BEFORE UPDATE OR DELETE ON app_open_additionality_opinion
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

CREATE TABLE app_open_additionality_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 criterion text NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 opinion_version integer NOT NULL,
 PRIMARY KEY (actor_id, command_key),
 FOREIGN KEY (project_id, criterion, opinion_version)
   REFERENCES app_open_additionality_opinion(project_id, criterion, version)
);
CREATE TRIGGER app_open_additionality_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_additionality_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK(action IN (
    'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
    'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
    'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
    'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
    'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
    'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
    'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
    'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
    'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION',
    'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING','SUBMIT_OPEN_MEASUREMENTS',
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD','VIEW_OPEN_PERIOD_ADMISSION',
    'SUBMIT_OPEN_PERIOD_EVIDENCE','REVIEW_OPEN_PERIOD_EVIDENCE',
    'SUBMIT_OPEN_MONITORING_EVENT','REVIEW_OPEN_MONITORING_EVENT',
    'VIEW_OPEN_METHOD_REVIEW','REVIEW_OPEN_METHOD_REVIEW',
    'VIEW_OPEN_E8_ENERGY_CANDIDATE','PREPARE_OPEN_E8_ENERGY_CANDIDATE',
    'VIEW_OPEN_EFEL_CANDIDATE','SUBMIT_OPEN_EFEL_CANDIDATE','REVIEW_OPEN_EFEL_CANDIDATE',
    'VIEW_OPEN_VT0011_WORKBOOK','SUBMIT_OPEN_VT0011_WORKBOOK','REVIEW_OPEN_VT0011_WORKBOOK',
    'VIEW_OPEN_SIMPLE_OM_A1','PREPARE_OPEN_SIMPLE_OM_A1',
    'VIEW_OPEN_E8_BINDING_CANDIDATE','PREPARE_OPEN_E8_BINDING_CANDIDATE',
    'VIEW_OPEN_E8_FLEET_SELECTION',
    'VIEW_OPEN_E8_RUN','RECORD_OPEN_E8_RUN',
    'VIEW_OPEN_PARAMETER_BINDING',
    'ADMIT_OPEN_PERIOD_BATCH',
    'REVIEW_OPEN_ADDITIONALITY'
 ));
