-- #96: dated human evidence of update and coverage, not normative admission.
CREATE TABLE app_open_ir_annual_temporal_evidence (
 assessment_id uuid NOT NULL REFERENCES app_open_ir_annual_evidence(assessment_id),
 metric text NOT NULL CHECK(metric IN ('EVR','AFEC','MPG','EF_FUEL')),
 updated_on date NOT NULL,
 valid_from date NOT NULL,
 valid_until date NOT NULL,
 evidence_locator text NOT NULL CHECK(length(btrim(evidence_locator)) BETWEEN 3 AND 2000),
 reference_sha256 bytea NOT NULL CHECK(octet_length(reference_sha256)=32),
 PRIMARY KEY(assessment_id,metric),
 CHECK(valid_from<valid_until)
);
CREATE TRIGGER app_open_ir_annual_temporal_immutable
 BEFORE UPDATE OR DELETE ON app_open_ir_annual_temporal_evidence
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
