-- CF-OPEN-009/#98 fourth slice: documentary review/readiness without normative admission.
-- Reuse the protected generic project evidence store (V53). No pilot data or
-- alternative ED/EC raw pipeline is created.
ALTER TABLE app_project_evidence_blob
 DROP CONSTRAINT app_project_evidence_blob_mime_type_check;
ALTER TABLE app_project_evidence_blob
 ADD CONSTRAINT app_project_evidence_blob_mime_type_check
 CHECK (mime_type IN ('text/csv','application/pdf'));

CREATE TABLE app_open_period_support_document (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 snapshot_id uuid NOT NULL,
 kind text NOT NULL CHECK (kind IN ('METER_CALIBRATION','SOURCE_RIGHTS')),
 subject_code text NOT NULL CHECK (subject_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
 evidence_blob_id uuid NOT NULL,
 evidence_reference text NOT NULL CHECK
   (char_length(btrim(evidence_reference)) BETWEEN 8 AND 300),
 submitted_by uuid NOT NULL REFERENCES app_identity(id),
 submitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,snapshot_id,kind,subject_code,evidence_blob_id),
 FOREIGN KEY(project_id,snapshot_id)
   REFERENCES app_open_period_qa_snapshot(project_id,id),
 FOREIGN KEY(project_id,evidence_blob_id)
   REFERENCES app_project_evidence_blob(project_id,id)
);
CREATE INDEX app_open_period_support_by_snapshot
 ON app_open_period_support_document(project_id,snapshot_id,kind,subject_code,submitted_at DESC,id DESC);

CREATE TABLE app_open_period_support_review (
 project_id text NOT NULL,
 document_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK(outcome IN ('DOCUMENT_CHECKED_INTERNAL','REJECTED')),
 reason text NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 15 AND 2000),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,document_id,version),
 FOREIGN KEY(project_id,document_id)
  REFERENCES app_open_period_support_document(project_id,id)
);

CREATE FUNCTION app_open_period_support_review_guard() RETURNS trigger AS $$
BEGIN
 IF EXISTS (
  SELECT 1 FROM app_open_period_support_document d
  JOIN app_open_period_qa_snapshot s
    ON s.project_id=d.project_id AND s.id=d.snapshot_id
  WHERE d.project_id=NEW.project_id AND d.id=NEW.document_id
    AND (d.submitted_by=NEW.reviewed_by OR s.created_by=NEW.reviewed_by)
 ) THEN
  RAISE EXCEPTION 'A document submitter cannot review their own evidence';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_period_support_review_guard_insert
 BEFORE INSERT ON app_open_period_support_review
 FOR EACH ROW EXECUTE FUNCTION app_open_period_support_review_guard();

CREATE TABLE app_open_period_support_upload_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 document_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,document_id)
  REFERENCES app_open_period_support_document(project_id,id)
);

CREATE TRIGGER app_open_period_support_document_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_support_document
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_period_support_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_support_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_period_support_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_support_upload_command
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
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD',
    'VIEW_OPEN_PERIOD_ADMISSION','SUBMIT_OPEN_PERIOD_EVIDENCE','REVIEW_OPEN_PERIOD_EVIDENCE'
 ));
