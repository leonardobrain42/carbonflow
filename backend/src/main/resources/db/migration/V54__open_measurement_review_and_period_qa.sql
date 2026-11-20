-- CF-OPEN-009 / #98, third slice. Internal QA review, never VVB/Verra acceptance.
-- Reviews and reconciled snapshots are immutable. No pilot table or E8 run is touched.
CREATE TABLE app_open_measurement_review (
 project_id text NOT NULL,
 batch_id uuid NOT NULL,
 source_line integer NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK (outcome IN ('ACCEPTED_FOR_QA','REJECTED')),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 2000),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,batch_id,source_line,version),
 FOREIGN KEY(project_id,batch_id,source_line)
  REFERENCES app_open_measurement_candidate(project_id,batch_id,source_line)
);
CREATE INDEX app_open_measurement_review_project
 ON app_open_measurement_review(project_id,reviewed_at DESC);

CREATE FUNCTION app_open_measurement_review_guard() RETURNS trigger AS $$
DECLARE original_author uuid; candidate_state text; batch_state text;
BEGIN
 SELECT b.submitted_by,b.status,c.state INTO original_author,batch_state,candidate_state
 FROM app_open_measurement_candidate c
 JOIN app_open_measurement_batch b
   ON b.project_id=c.project_id AND b.id=c.batch_id
 WHERE c.project_id=NEW.project_id AND c.batch_id=NEW.batch_id
   AND c.source_line=NEW.source_line FOR SHARE OF b,c;
 IF original_author IS NULL THEN
   RAISE EXCEPTION 'Candidate not found for review';
 END IF;
 IF original_author=NEW.reviewed_by THEN
   RAISE EXCEPTION 'Independent measurement reviewer required';
 END IF;
 IF NEW.outcome='ACCEPTED_FOR_QA' AND
    (batch_state<>'CANDIDATE' OR candidate_state<>'CANDIDATE') THEN
   RAISE EXCEPTION 'Quarantined batch or candidate cannot pass QA';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_measurement_review_guard_insert
 BEFORE INSERT ON app_open_measurement_review
 FOR EACH ROW EXECUTE FUNCTION app_open_measurement_review_guard();
CREATE TRIGGER app_open_measurement_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_measurement_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

-- A snapshot is an internal QA artifact only. It can NEVER authorize E8 or VCU.
CREATE TABLE app_open_period_qa_snapshot (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_start timestamptz NOT NULL,
 period_end timestamptz NOT NULL,
 ed_kwh numeric(30,12) NOT NULL CHECK(ed_kwh>0),
 ec_kwh numeric(30,12) NOT NULL CHECK(ec_kwh>0),
 source_sha256 bytea NOT NULL CHECK(octet_length(source_sha256)=32),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 status text NOT NULL DEFAULT 'INTERNAL_QA_ONLY'
   CHECK(status='INTERNAL_QA_ONLY'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 CHECK(period_end>period_start),
 UNIQUE(project_id,period_start,period_end,source_sha256),
 UNIQUE(project_id,id),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment)
);
CREATE TABLE app_open_period_qa_snapshot_line (
 project_id text NOT NULL,
 snapshot_id uuid NOT NULL,
 batch_id uuid NOT NULL,
 source_line integer NOT NULL,
 review_version integer NOT NULL,
 PRIMARY KEY(project_id,snapshot_id,batch_id,source_line),
 FOREIGN KEY(project_id,snapshot_id)
   REFERENCES app_open_period_qa_snapshot(project_id,id),
 FOREIGN KEY(project_id,batch_id,source_line)
   REFERENCES app_open_measurement_review(project_id,batch_id,source_line,version)
);
CREATE TRIGGER app_open_period_qa_snapshot_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_qa_snapshot
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_period_qa_snapshot_line_immutable
 BEFORE UPDATE OR DELETE ON app_open_period_qa_snapshot_line
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
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD'
 ));
