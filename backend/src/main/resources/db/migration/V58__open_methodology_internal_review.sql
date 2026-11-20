-- CF-OPEN-009/#98 — independent, versioned INTERNAL methodology checklist
-- of a VM0038 v1.1 MR draft. Never a VVB / Verra verdict or E8 admission.
CREATE TABLE app_open_method_review (
 project_id text NOT NULL,
 dossier_code text NOT NULL,
 dossier_version integer NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 snapshot_id uuid NOT NULL,
 methodology text NOT NULL DEFAULT 'VM0038' CHECK(methodology='VM0038'),
 methodology_edition text NOT NULL DEFAULT '1.1' CHECK(methodology_edition='1.1'),
 outcome text NOT NULL CHECK(outcome IN ('CHECKED_INTERNAL','RETURNED')),
 rationale text NOT NULL CHECK(char_length(btrim(rationale)) BETWEEN 15 AND 2000),
 checklist jsonb NOT NULL CHECK(jsonb_typeof(checklist)='array'),
 evidence_sha256 bytea NOT NULL CHECK(octet_length(evidence_sha256)=32),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,dossier_code,dossier_version,version),
 FOREIGN KEY(project_id,dossier_code,dossier_version)
    REFERENCES app_open_mr_dossier_revision(project_id,dossier_code,version),
 FOREIGN KEY(project_id,snapshot_id)
    REFERENCES app_open_period_qa_snapshot(project_id,id)
);
CREATE INDEX app_open_method_review_project ON app_open_method_review(project_id,dossier_code,reviewed_at DESC);

-- A normative internal assessment is independent of the MR author, the
-- immutable QA snapshot creator, the original measurement submitters,
-- and the evidence submitters. This rule is enforced in PostgreSQL as well.
CREATE FUNCTION app_open_method_review_independence() RETURNS trigger AS $$
DECLARE mr_author uuid; mr_snapshot uuid; qa_author uuid;
BEGIN
 SELECT authored_by,snapshot_id INTO mr_author,mr_snapshot
 FROM app_open_mr_dossier_revision
 WHERE project_id=NEW.project_id AND dossier_code=NEW.dossier_code
   AND version=NEW.dossier_version FOR SHARE;
 IF mr_author IS NULL OR mr_snapshot IS DISTINCT FROM NEW.snapshot_id
    OR mr_author=NEW.reviewed_by THEN
   RAISE EXCEPTION 'Method review must reference matching MR revision and an independent reviewer';
 END IF;
 SELECT created_by INTO qa_author FROM app_open_period_qa_snapshot
 WHERE project_id=NEW.project_id AND id=NEW.snapshot_id FOR SHARE;
 IF qa_author IS NULL OR qa_author=NEW.reviewed_by THEN
   RAISE EXCEPTION 'Method reviewer cannot be the QA snapshot creator';
 END IF;
 IF EXISTS(
   SELECT 1 FROM app_open_period_qa_snapshot_line l
   JOIN app_open_measurement_batch b ON b.project_id=l.project_id AND b.id=l.batch_id
   WHERE l.project_id=NEW.project_id AND l.snapshot_id=NEW.snapshot_id
     AND b.submitted_by=NEW.reviewed_by
 ) THEN
   RAISE EXCEPTION 'Method reviewer cannot have submitted measurement inputs';
 END IF;
 IF EXISTS(
   SELECT 1 FROM app_open_period_support_document d
   WHERE d.project_id=NEW.project_id AND d.snapshot_id=NEW.snapshot_id
     AND d.submitted_by=NEW.reviewed_by
 ) THEN
   RAISE EXCEPTION 'Method reviewer cannot have submitted documentary evidence';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_method_review_independence_insert
 BEFORE INSERT ON app_open_method_review
 FOR EACH ROW EXECUTE FUNCTION app_open_method_review_independence();
CREATE TRIGGER app_open_method_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_method_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

CREATE TABLE app_open_method_review_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128} app_project_technical_access_event_action_check;
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
    'VIEW_OPEN_METHOD_REVIEW','REVIEW_OPEN_METHOD_REVIEW'
 ));
),
 project_id text NOT NULL,
 dossier_code text NOT NULL,
 dossier_version integer NOT NULL,
 review_version integer NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,dossier_code,dossier_version,review_version)
   REFERENCES app_open_method_review(project_id,dossier_code,dossier_version,version)
);
CREATE TRIGGER app_open_method_review_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_method_review_command
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
    'VIEW_OPEN_METHOD_REVIEW','REVIEW_OPEN_METHOD_REVIEW'
 ));
