-- Independent internal opinion for the exact immutable Dy assessment.
CREATE TABLE app_open_dy_review (
 assessment_id uuid PRIMARY KEY REFERENCES app_open_dy_assessment(id),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 outcome text NOT NULL CHECK (outcome IN ('CHECKED_INTERNAL','NEEDS_INFORMATION','REJECTED')),
 assessment_sha256 bytea NOT NULL CHECK (octet_length(assessment_sha256)=32),
 rule_reference text NOT NULL CHECK (length(btrim(rule_reference)) BETWEEN 8 AND 300),
 evidence_references text NOT NULL CHECK (length(btrim(evidence_references)) BETWEEN 3 AND 2000),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 2500),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 command_sha256 bytea NOT NULL CHECK (octet_length(command_sha256)=32),
 UNIQUE (reviewer_id,command_key)
);
CREATE TRIGGER app_open_dy_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
