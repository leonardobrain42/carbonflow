-- CF-OPEN-009 / #98. Independent admission of one measurement batch inside a
-- frozen period snapshot. This is not VVB acceptance, not E8 input, and not a VCU.
CREATE TABLE app_open_period_batch_admission (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 snapshot_id uuid NOT NULL,
 batch_id uuid NOT NULL,
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK (outcome IN ('ADMITTED_FOR_PERIOD','REJECTED')),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 2000),
 snapshot_sha256 bytea NOT NULL CHECK (octet_length(snapshot_sha256)=32),
 admitted_by uuid NOT NULL REFERENCES app_identity(id),
 admitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (usable_for_e8=false),
 UNIQUE (project_id,snapshot_id,batch_id,version),
 UNIQUE (project_id,id),
 FOREIGN KEY (project_id,snapshot_id)
   REFERENCES app_open_period_qa_snapshot(project_id,id),
 FOREIGN KEY (project_id,batch_id)
   REFERENCES app_open_measurement_batch(project_id,id),
 FOREIGN KEY (project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment)
);

CREATE FUNCTION app_open_period_batch_admission_guard() RETURNS trigger AS $$
DECLARE submitter uuid; freezer uuid;
BEGIN
 SELECT b.submitted_by,s.created_by INTO submitter,freezer
 FROM app_open_period_qa_snapshot s
 JOIN app_open_measurement_batch b
   ON b.project_id=s.project_id AND b.id=NEW.batch_id
 WHERE s.project_id=NEW.project_id AND s.id=NEW.snapshot_id
 FOR SHARE OF s,b;
 IF submitter IS NULL THEN
   RAISE EXCEPTION 'Batch is not part of this period snapshot';
 END IF;
 IF NOT EXISTS (
   SELECT 1 FROM app_open_period_qa_snapshot_line
   WHERE project_id=NEW.project_id AND snapshot_id=NEW.snapshot_id
     AND batch_id=NEW.batch_id) THEN
   RAISE EXCEPTION 'Batch is not part of this period snapshot';
 END IF;
 IF submitter=NEW.admitted_by OR freezer=NEW.admitted_by THEN
   RAISE EXCEPTION 'Independent batch admission required';
 END IF;
 IF EXISTS (
   SELECT 1
   FROM app_open_period_qa_snapshot_line l
   JOIN app_open_measurement_review r
     ON r.project_id=l.project_id AND r.batch_id=l.batch_id
    AND r.source_line=l.source_line AND r.version=l.review_version
   WHERE l.project_id=NEW.project_id AND l.snapshot_id=NEW.snapshot_id
     AND l.batch_id=NEW.batch_id AND r.reviewed_by=NEW.admitted_by) THEN
   RAISE EXCEPTION 'Line reviewer cannot admit the same batch';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_period_batch_admission_guard_insert
 BEFORE INSERT ON app_open_period_batch_admission
 FOR EACH ROW EXECUTE FUNCTION app_open_period_batch_admission_guard();
CREATE TRIGGER app_open_period_batch_admission_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_batch_admission
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

CREATE TABLE app_open_period_batch_admission_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 snapshot_id uuid NOT NULL,
 admission_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (actor_id,command_key),
 FOREIGN KEY (project_id,admission_id)
   REFERENCES app_open_period_batch_admission(project_id,id)
);
CREATE TRIGGER app_open_period_batch_admission_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_batch_admission_command
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
    'ADMIT_OPEN_PERIOD_BATCH'
 ));
