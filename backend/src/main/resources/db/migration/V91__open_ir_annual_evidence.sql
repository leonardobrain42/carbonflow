-- #96: pair of canonical annual assessments. Evidence of source-year continuity,
-- not proof of timely historical updates or normative admission.
ALTER TABLE app_open_ir_assessment ADD CONSTRAINT app_open_ir_annual_target
 UNIQUE(id,project_id,project_year,source_year,assessment_sha256);
CREATE TABLE app_open_ir_annual_evidence (
 assessment_id uuid PRIMARY KEY REFERENCES app_open_ir_review(assessment_id),
 prior_assessment_id uuid NOT NULL,
 project_id text NOT NULL,
 project_year integer NOT NULL,
 source_year integer NOT NULL,
 prior_project_year integer NOT NULL,
 prior_source_year integer NOT NULL,
 assessment_sha256 bytea NOT NULL CHECK(octet_length(assessment_sha256)=32),
 prior_assessment_sha256 bytea NOT NULL CHECK(octet_length(prior_assessment_sha256)=32),
 evidence_sha256 bytea NOT NULL CHECK(octet_length(evidence_sha256)=32),
 policy text NOT NULL CHECK(policy='OPEN-IR-ANNUAL-EVIDENCE@1'),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY(assessment_id,project_id,project_year,source_year,assessment_sha256)
   REFERENCES app_open_ir_assessment(id,project_id,project_year,source_year,assessment_sha256),
 FOREIGN KEY(prior_assessment_id,project_id,prior_project_year,prior_source_year,prior_assessment_sha256)
   REFERENCES app_open_ir_assessment(id,project_id,project_year,source_year,assessment_sha256),
 CHECK(assessment_id<>prior_assessment_id),
 CHECK(project_year=prior_project_year+1 AND source_year=prior_source_year+1)
);
CREATE TRIGGER app_open_ir_annual_evidence_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_annual_evidence
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
