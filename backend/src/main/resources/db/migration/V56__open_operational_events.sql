-- CF-OPEN-009 / #98: equipment and telemetry operational occurrences.
-- Recorded facts remain append-only and can only be dispositioned by an
-- independent REV. These never constitute normative admission or E8 input.
CREATE TABLE app_open_operational_event (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 kind text NOT NULL CHECK(kind IN
   ('SOURCE_OUTAGE','METER_MAINTENANCE','CLOCK_DRIFT','DATA_GAP')),
 subject_type text NOT NULL CHECK(subject_type IN ('SOURCE','METER','CHARGER')),
 subject_code text NOT NULL CHECK
   (subject_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
 period_start timestamptz NOT NULL,
 period_end timestamptz NOT NULL,
 incident_reference text NOT NULL CHECK
   (char_length(btrim(incident_reference)) BETWEEN 8 AND 300),
 description text NOT NULL CHECK
   (char_length(btrim(description)) BETWEEN 15 AND 2000),
 submitted_by uuid NOT NULL REFERENCES app_identity(id),
 submitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK (period_end>period_start AND period_end<=period_start+interval '31 days'),
 CHECK ((kind='SOURCE_OUTAGE' AND subject_type='SOURCE')
    OR (kind IN ('METER_MAINTENANCE','CLOCK_DRIFT') AND subject_type='METER')
    OR (kind='DATA_GAP' AND subject_type IN ('SOURCE','METER','CHARGER'))),
 UNIQUE(project_id,id),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment)
);
CREATE INDEX app_open_operational_event_period
 ON app_open_operational_event(project_id,period_start,period_end);
CREATE INDEX app_open_operational_event_subject
 ON app_open_operational_event(project_id,subject_type,subject_code);

CREATE TABLE app_open_operational_event_review (
 project_id text NOT NULL,
 event_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK
   (outcome IN ('CONFIRMED_BLOCKING','CLEARED_FOR_INTERNAL_QA')),
 reason text NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 15 AND 2000),
 evidence_reference text NOT NULL CHECK
   (char_length(btrim(evidence_reference)) BETWEEN 8 AND 300),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,event_id,version),
 FOREIGN KEY(project_id,event_id)
   REFERENCES app_open_operational_event(project_id,id)
);
CREATE FUNCTION app_open_operational_event_review_guard() RETURNS trigger AS $$
BEGIN
 IF EXISTS (
   SELECT 1 FROM app_open_operational_event e
   WHERE e.project_id=NEW.project_id AND e.id=NEW.event_id
     AND e.submitted_by=NEW.reviewed_by
 ) THEN
  RAISE EXCEPTION 'Incident submitter cannot review their own operational event';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_operational_event_review_guard_insert
 BEFORE INSERT ON app_open_operational_event_review
 FOR EACH ROW EXECUTE FUNCTION app_open_operational_event_review_guard();

CREATE TABLE app_open_operational_event_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 event_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,event_id)
   REFERENCES app_open_operational_event(project_id,id)
);
CREATE TRIGGER app_open_operational_event_immutable
 BEFORE UPDATE OR DELETE ON app_open_operational_event
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_operational_event_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_operational_event_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_operational_event_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_operational_event_command
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
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD',
    'VIEW_OPEN_PERIOD_ADMISSION','SUBMIT_OPEN_PERIOD_EVIDENCE','REVIEW_OPEN_PERIOD_EVIDENCE',
    'SUBMIT_OPEN_MONITORING_EVENT','REVIEW_OPEN_MONITORING_EVENT'
 ));
