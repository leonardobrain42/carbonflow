-- #97: project-scoped, immutable candidate-only bridge between reviewed
-- hourly ED/EC and claimed hourly EFel. Does NOT admit factors or run E8.
CREATE TABLE app_open_e8_input_binding_candidate (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 energy_candidate_id uuid NOT NULL,
 efel_candidate_id uuid NOT NULL,
 energy_sha256 bytea NOT NULL CHECK(octet_length(energy_sha256)=32),
 efel_sha256 bytea NOT NULL CHECK(octet_length(efel_sha256)=32),
 efel_review_version integer NOT NULL CHECK(efel_review_version>0),
 input_sha256 bytea NOT NULL CHECK(octet_length(input_sha256)=32),
 hour_count integer NOT NULL CHECK(hour_count BETWEEN 1 AND 744),
 slot_count integer NOT NULL CHECK(slot_count BETWEEN 1 AND 74400),
 state text NOT NULL DEFAULT 'CANDIDATE_NOT_ADMITTED'
     CHECK(state='CANDIDATE_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,period_code,version),
 UNIQUE(project_id,period_code,energy_candidate_id,efel_candidate_id,efel_review_version),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,energy_candidate_id)
   REFERENCES app_open_e8_energy_candidate(project_id,id),
 FOREIGN KEY(project_id,efel_candidate_id)
   REFERENCES app_open_efel_hourly_candidate(project_id,id),
 FOREIGN KEY(project_id,efel_candidate_id,efel_review_version)
   REFERENCES app_open_efel_hourly_review(project_id,candidate_id,version)
);
CREATE INDEX app_open_e8_input_binding_lookup
 ON app_open_e8_input_binding_candidate(project_id,period_code,version DESC);

CREATE TABLE app_open_e8_input_binding_hour (
 project_id text NOT NULL,
 binding_id uuid NOT NULL,
 hour_start timestamptz NOT NULL,
 charger_count integer NOT NULL CHECK(charger_count BETWEEN 1 AND 100),
 ed_kwh numeric(30,12) NOT NULL CHECK(ed_kwh>0),
 ec_kwh numeric(30,12) NOT NULL CHECK(ec_kwh>=ed_kwh),
 efel_tco2e_per_kwh numeric(30,18) NOT NULL CHECK(efel_tco2e_per_kwh>=0),
 PRIMARY KEY(project_id,binding_id,hour_start),
 FOREIGN KEY(project_id,binding_id)
   REFERENCES app_open_e8_input_binding_candidate(project_id,id)
);

CREATE TABLE app_open_e8_input_binding_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 binding_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,binding_id)
   REFERENCES app_open_e8_input_binding_candidate(project_id,id)
);
CREATE TRIGGER app_open_e8_binding_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_input_binding_candidate
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_binding_hour_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_input_binding_hour
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_binding_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_input_binding_command
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
    'VIEW_OPEN_E8_BINDING_CANDIDATE','PREPARE_OPEN_E8_BINDING_CANDIDATE'
 ));
