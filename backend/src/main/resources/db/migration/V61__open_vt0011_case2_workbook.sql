-- CF-OPEN-007 #96 / V61: an explanatory VT0011 v1.0 + TOOL07 v7.0
-- Case 2 workbook, tied to an existing SIN EFel candidate.
-- No unverified OM becomes a normative emission factor or E8 input.
CREATE TABLE app_open_vt0011_case2_workbook (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 efel_candidate_id uuid NOT NULL,
 efel_review_version integer NOT NULL CHECK(efel_review_version>0),
 efel_source_sha256 bytea NOT NULL CHECK(octet_length(efel_source_sha256)=32),
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 vt0011_version text NOT NULL DEFAULT '1.0' CHECK(vt0011_version='1.0'),
 tool07_version text NOT NULL DEFAULT '7.0' CHECK(tool07_version='7.0'),
 grid_system text NOT NULL DEFAULT 'SIN' CHECK(grid_system='SIN'),
 case_code text NOT NULL DEFAULT 'CASE_2_INCREASE_GRID_CONSUMPTION'
   CHECK(case_code='CASE_2_INCREASE_GRID_CONSUMPTION'),
 om_method text NOT NULL CHECK(om_method IN
   ('SIMPLE_OM','SIMPLE_ADJUSTED_OM','DISPATCH_DATA_ANALYSIS_OM','AVERAGE_OM')),
 om_vintage_mode text NOT NULL CHECK(om_vintage_mode IN ('EX_ANTE','EX_POST')),
 om_data_year integer NOT NULL CHECK(om_data_year BETWEEN 1990 AND 2100),
 om_factor_tco2_per_mwh numeric(30,12) NOT NULL CHECK(om_factor_tco2_per_mwh>=0),
 om_weight numeric(3,2) NOT NULL DEFAULT 1.00 CHECK(om_weight=1.00),
 bm_weight numeric(3,2) NOT NULL DEFAULT 0.00 CHECK(bm_weight=0.00),
 om_source_reference text NOT NULL
   CHECK(char_length(btrim(om_source_reference)) BETWEEN 15 AND 600),
 om_dataset_sha256_claimed bytea NOT NULL
   CHECK(octet_length(om_dataset_sha256_claimed)=32),
 om_method_justification text NOT NULL
   CHECK(char_length(btrim(om_method_justification)) BETWEEN 30 AND 2000),
 grid_boundary_evidence text NOT NULL
   CHECK(char_length(btrim(grid_boundary_evidence)) BETWEEN 15 AND 600),
 sample_selection_evidence text NOT NULL
   CHECK(char_length(btrim(sample_selection_evidence)) BETWEEN 15 AND 600),
 import_treatment_evidence text NOT NULL
   CHECK(char_length(btrim(import_treatment_evidence)) BETWEEN 15 AND 600),
 vintage_evidence text NOT NULL
   CHECK(char_length(btrim(vintage_evidence)) BETWEEN 15 AND 600),
 methodology_note text NOT NULL
   CHECK(char_length(btrim(methodology_note)) BETWEEN 30 AND 2000),
 content_sha256 bytea NOT NULL CHECK(octet_length(content_sha256)=32),
 state text NOT NULL DEFAULT 'INTERNAL_WORKBOOK_NOT_ADMITTED'
   CHECK(state='INTERNAL_WORKBOOK_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 authored_by uuid NOT NULL REFERENCES app_identity(id),
 authored_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,efel_candidate_id,version),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,efel_candidate_id)
   REFERENCES app_open_efel_hourly_candidate(project_id,id),
 FOREIGN KEY(project_id,efel_candidate_id,efel_review_version)
   REFERENCES app_open_efel_hourly_review(project_id,candidate_id,version)
);
CREATE INDEX app_open_vt0011_workbook_period
 ON app_open_vt0011_case2_workbook(project_id,period_code,authored_at DESC);

CREATE TABLE app_open_vt0011_case2_review (
 project_id text NOT NULL,
 workbook_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK(outcome IN ('CHECKED_INTERNAL','RETURNED')),
 rationale text NOT NULL
   CHECK(char_length(btrim(rationale)) BETWEEN 30 AND 2000),
 evidence_reference text NOT NULL
   CHECK(char_length(btrim(evidence_reference)) BETWEEN 15 AND 600),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,workbook_id,version),
 FOREIGN KEY(project_id,workbook_id)
   REFERENCES app_open_vt0011_case2_workbook(project_id,id)
);
-- Enforce separation at the database as well as in project permissions:
-- neither the workbook author nor the EFel source submitter can review.
CREATE FUNCTION app_open_vt0011_case2_independent_review() RETURNS trigger AS $$
DECLARE dossier_author uuid; source_author uuid;
BEGIN
 SELECT w.authored_by, e.submitted_by INTO dossier_author,source_author
 FROM app_open_vt0011_case2_workbook w
 JOIN app_open_efel_hourly_candidate e
   ON e.project_id=w.project_id AND e.id=w.efel_candidate_id
 WHERE w.project_id=NEW.project_id AND w.id=NEW.workbook_id FOR SHARE OF w,e;
 IF dossier_author IS NULL OR NEW.reviewer_id=dossier_author
   OR NEW.reviewer_id=source_author THEN
   RAISE EXCEPTION 'VT0011 workbook reviewer is not independent';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_vt0011_case2_review_guard
 BEFORE INSERT ON app_open_vt0011_case2_review
 FOR EACH ROW EXECUTE FUNCTION app_open_vt0011_case2_independent_review();

CREATE TABLE app_open_vt0011_case2_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 workbook_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('SUBMIT','REVIEW')),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 review_version integer,
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,workbook_id)
   REFERENCES app_open_vt0011_case2_workbook(project_id,id),
 FOREIGN KEY(project_id,workbook_id,review_version)
   REFERENCES app_open_vt0011_case2_review(project_id,workbook_id,version),
 CHECK((action='SUBMIT' AND review_version IS NULL) OR
       (action='REVIEW' AND review_version IS NOT NULL))
);
CREATE TRIGGER app_open_vt0011_case2_workbook_immutable
 BEFORE UPDATE OR DELETE ON app_open_vt0011_case2_workbook
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_vt0011_case2_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_vt0011_case2_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_vt0011_case2_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_vt0011_case2_command
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
    'VIEW_OPEN_VT0011_WORKBOOK','SUBMIT_OPEN_VT0011_WORKBOOK','REVIEW_OPEN_VT0011_WORKBOOK'
 ));
