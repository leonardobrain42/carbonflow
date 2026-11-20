-- CF-OPEN-008 #97 / CF-OPEN-009 #98 — immutable, NOT ADMITTED E8
-- energy staging from genuine OPEN QA snapshot and independent internal review.
-- This table is never an admissible energy input for equations or issuance.
CREATE TABLE app_open_e8_energy_candidate (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 dossier_code text NOT NULL,
 dossier_version integer NOT NULL,
 method_review_version integer NOT NULL,
 snapshot_id uuid NOT NULL,
 source_sha256 bytea NOT NULL CHECK(octet_length(source_sha256)=32),
 ed_kwh numeric(30,12) NOT NULL CHECK(ed_kwh>0),
 ec_kwh numeric(30,12) NOT NULL CHECK(ec_kwh>0),
 hour_count integer NOT NULL CHECK(hour_count BETWEEN 1 AND 744),
 slot_count integer NOT NULL CHECK(slot_count BETWEEN 1 AND 74400),
 status text NOT NULL DEFAULT 'NOT_ADMITTED'
  CHECK(status='NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,dossier_code,dossier_version,method_review_version,source_sha256),
 FOREIGN KEY(project_id,organization_id,environment)
  REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,dossier_code,dossier_version)
  REFERENCES app_open_mr_dossier_revision(project_id,dossier_code,version),
 FOREIGN KEY(project_id,dossier_code,dossier_version,method_review_version)
  REFERENCES app_open_method_review(project_id,dossier_code,dossier_version,version),
 FOREIGN KEY(project_id,snapshot_id)
  REFERENCES app_open_period_qa_snapshot(project_id,id)
);
CREATE INDEX app_open_e8_energy_candidate_lookup
 ON app_open_e8_energy_candidate(project_id,dossier_code,created_at DESC);

CREATE TABLE app_open_e8_energy_candidate_hour (
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 charger_code text NOT NULL,
 hour_start timestamptz NOT NULL,
 ed_kwh numeric(30,12) NOT NULL CHECK(ed_kwh>0),
 ec_kwh numeric(30,12) NOT NULL CHECK(ec_kwh>0),
 ed_batch_id uuid NOT NULL,
 ed_source_line integer NOT NULL,
 ed_review_version integer NOT NULL,
 ec_batch_id uuid NOT NULL,
 ec_source_line integer NOT NULL,
 ec_review_version integer NOT NULL,
 PRIMARY KEY(project_id,candidate_id,charger_code,hour_start),
 FOREIGN KEY(project_id,candidate_id)
  REFERENCES app_open_e8_energy_candidate(project_id,id),
 FOREIGN KEY(project_id,ed_batch_id,ed_source_line,ed_review_version)
  REFERENCES app_open_measurement_review(project_id,batch_id,source_line,version),
 FOREIGN KEY(project_id,ec_batch_id,ec_source_line,ec_review_version)
  REFERENCES app_open_measurement_review(project_id,batch_id,source_line,version),
 CHECK(ec_kwh>=ed_kwh)
);
CREATE TABLE app_open_e8_energy_candidate_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,candidate_id)
  REFERENCES app_open_e8_energy_candidate(project_id,id)
);
CREATE TRIGGER app_open_e8_energy_candidate_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_energy_candidate
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_energy_candidate_hour_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_energy_candidate_hour
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_energy_candidate_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_energy_candidate_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK (action IN (
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
    'VIEW_OPEN_E8_ENERGY_CANDIDATE','PREPARE_OPEN_E8_ENERGY_CANDIDATE'
 ));
