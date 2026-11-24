-- #96: independent preparatory technical opinion, never E8 admission.
ALTER TABLE app_open_ir_assessment ADD CONSTRAINT app_open_ir_assessment_review_target
 UNIQUE(id,assessment_sha256,author_id);
CREATE TABLE app_open_ir_review (
 assessment_id uuid PRIMARY KEY,
 assessment_sha256 bytea NOT NULL CHECK (octet_length(assessment_sha256)=32),
 author_id uuid NOT NULL,
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 outcome text NOT NULL CHECK (outcome IN ('CHECKED_INTERNAL','NEEDS_INFORMATION','REJECTED')),
 rule_reference text NOT NULL CHECK (rule_reference='VM0038/1.1/10.1'),
 annual_update_basis text NOT NULL CHECK (annual_update_basis IN ('PLAN_ONLY','OBSERVED_UPDATE','INSUFFICIENT')),
 evidence_locator text NOT NULL CHECK (length(btrim(evidence_locator)) BETWEEN 3 AND 2000),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 2500),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 command_sha256 bytea NOT NULL CHECK (octet_length(command_sha256)=32),
 UNIQUE(reviewer_id,command_key),
 FOREIGN KEY(assessment_id,assessment_sha256,author_id)
   REFERENCES app_open_ir_assessment(id,assessment_sha256,author_id),
 CHECK (author_id<>reviewer_id),
 CHECK (outcome<>'CHECKED_INTERNAL' OR annual_update_basis='OBSERVED_UPDATE')
);
CREATE TRIGGER app_open_ir_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
