-- CF-OPEN-004: independent project-scoped declarations; no real norm verdict and no E8 promotion.
CREATE TABLE app_project_open_applicability_revision (
 project_id text NOT NULL REFERENCES app_project(id),
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 version integer NOT NULL CHECK (version > 0),
 triage_version integer NOT NULL CHECK (triage_version >= 0),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 unrestricted_bev_access boolean,
 restricted_to_identified_vehicles boolean,
 private_land boolean,
 charger_type text CHECK (charger_type IN ('DCFC','AC','MIXED','UNKNOWN')),
 propulsion text CHECK (propulsion IN ('BEV','PHEV','MIXED','UNKNOWN')),
 electricity_source text CHECK (electricity_source IN ('SIN','OTHER_GRID','ON_SITE','MIXED','UNKNOWN')),
 associated_infrastructure boolean,
 v2g boolean,
 charging_owner text,
 carbon_rights_contract_ref text,
 driver_disclosure_channel text CHECK
  (driver_disclosure_channel IN ('ON_CHARGER','APP_OR_WEBSITE','TERMS_OF_SERVICE','NOT_DECLARED')),
 driver_disclosure_evidence_ref text,
 driver_disclosure_effective_on date,
 double_claiming_check text CHECK
  (double_claiming_check IN ('UNKNOWN','REVIEW_REQUIRED','POTENTIAL_OVERLAP','NO_OVERLAP_DECLARED')),
 effective_from date,
 explanation text NOT NULL DEFAULT '',
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,version),
 FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment)
);

CREATE TABLE app_project_open_applicability_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL,
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 revision integer NOT NULL,
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,revision) REFERENCES app_project_open_applicability_revision(project_id,version)
);

ALTER TABLE app_project_technical_access_event DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event ADD CONSTRAINT app_project_technical_access_event_action_check
 CHECK (action IN (
      'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
      'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
      'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
      'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
      'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
      'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
      'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
      'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
      'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY'
 ));
