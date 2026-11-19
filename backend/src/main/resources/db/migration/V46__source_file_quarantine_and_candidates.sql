-- CF-OPEN-003 / #92: licensed, encrypted original file quarantine and scoped candidate data.
-- No dataset reaches the real E8 engine by virtue of this schema.
CREATE TABLE app_platform_source_object (
 id uuid PRIMARY KEY,
 submission_id uuid NOT NULL REFERENCES app_platform_source_submission(id),
 organization_id uuid NOT NULL REFERENCES app_organization(id),
 uploaded_by uuid NOT NULL REFERENCES app_identity(id),
 license_id uuid NOT NULL REFERENCES app_content_license(id),
 license_version integer NOT NULL CHECK (license_version > 0),
 file_name text NOT NULL CHECK (length(file_name) BETWEEN 1 AND 100),
 file_format text NOT NULL CHECK (file_format IN ('CSV','XLSX')),
 sha256 bytea NOT NULL CHECK (octet_length(sha256)=32),
 bytes_count integer NOT NULL CHECK (bytes_count BETWEEN 16 AND 2097152),
 nonce bytea CHECK (nonce IS NULL OR octet_length(nonce)=12),
 encrypted bytea,
 state text NOT NULL DEFAULT 'QUARANTINED' CHECK (state IN
   ('QUARANTINED','NORMALIZED_CANDIDATE','SCHEMA_REJECTED','TECHNICAL_REVIEW_REQUIRED',
    'APPROVED_FOR_SCOPE','REJECTED_FOR_SCOPE','SUSPENDED')),
 issues text[] NOT NULL DEFAULT '{}'::text[],
 parsed_rows integer NOT NULL DEFAULT 0 CHECK (parsed_rows >= 0),
 parser_version text,
 uploaded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 parsed_at timestamptz,
 purged_at timestamptz,
 CHECK ((purged_at IS NOT NULL) = (nonce IS NULL AND encrypted IS NULL)),
 CHECK ((purged_at IS NOT NULL) OR octet_length(encrypted)=bytes_count+16),
 FOREIGN KEY(license_id,license_version) REFERENCES app_content_license_decision(license_id,version)
);
-- Uniqueness applies to retained objects only; a purged object may be re-imported
-- under a newly and independently approved licence.
CREATE UNIQUE INDEX app_platform_source_object_active_hash
 ON app_platform_source_object(submission_id,organization_id,sha256)
 WHERE purged_at IS NULL;
CREATE INDEX app_platform_source_object_scope ON app_platform_source_object(submission_id,organization_id,uploaded_at DESC);
CREATE INDEX app_platform_source_object_license ON app_platform_source_object(license_id);

CREATE TABLE app_platform_source_candidate (
 object_id uuid NOT NULL REFERENCES app_platform_source_object(id) ON DELETE CASCADE,
 row_index integer NOT NULL CHECK (row_index BETWEEN 2 AND 2001),
 record_key text NOT NULL CHECK (char_length(record_key) BETWEEN 1 AND 100),
 metric text NOT NULL CHECK (char_length(metric) BETWEEN 1 AND 80),
 period_year integer NOT NULL CHECK (period_year BETWEEN 1900 AND 2100),
 geography text NOT NULL CHECK (char_length(geography) BETWEEN 2 AND 64),
 unit text NOT NULL CHECK (char_length(unit) BETWEEN 1 AND 40),
 value_numeric numeric(30,12) NOT NULL,
 locator text NOT NULL,
 PRIMARY KEY(object_id,row_index),
 UNIQUE(object_id,record_key,metric,period_year,geography,unit)
);
CREATE INDEX app_platform_source_candidate_dimension ON
 app_platform_source_candidate(object_id,metric,period_year,geography,unit);

-- Decision is narrowly scoped; it does not create the ProjectSelection or enable calculations.
CREATE TABLE app_platform_source_assessment (
 id uuid PRIMARY KEY,
 object_id uuid NOT NULL REFERENCES app_platform_source_object(id),
 version integer NOT NULL DEFAULT 1 CHECK (version=1),
 outcome text NOT NULL CHECK (outcome IN ('APPROVED_FOR_SCOPE','REJECTED_FOR_SCOPE','NEEDS_INFORMATION')),
 methodology text NOT NULL,
 methodology_edition text NOT NULL,
 scenario text NOT NULL,
 blocked_capability text NOT NULL,
 metric text NOT NULL,
 period_year integer NOT NULL,
 geography text NOT NULL,
 unit text NOT NULL,
 evidence_locator text NOT NULL CHECK (char_length(btrim(evidence_locator)) BETWEEN 5 AND 300),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 2000),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(object_id,methodology,methodology_edition,scenario,blocked_capability,metric,period_year,geography,unit)
);

-- Any new REVOKED/DENIED decision immediately removes encrypted originals and derived rows
-- associated with that license. Metadata, audit and verified digest survive without original bytes.
CREATE FUNCTION app_platform_source_revoke_content() RETURNS trigger AS $$
BEGIN
 IF NEW.outcome IN ('REVOKED','DENIED') THEN
   DELETE FROM app_platform_source_candidate c
   USING app_platform_source_object o
   WHERE c.object_id=o.id AND o.license_id=NEW.license_id;
   UPDATE app_platform_source_object
     SET encrypted=NULL, nonce=NULL, purged_at=statement_timestamp(),state='SUSPENDED',
         issues=ARRAY['Licença revogada ou negada; conteúdo e derivados removidos']
   WHERE license_id=NEW.license_id AND purged_at IS NULL;
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_source_content_revoked
AFTER INSERT ON app_content_license_decision
FOR EACH ROW EXECUTE FUNCTION app_platform_source_revoke_content();

ALTER TABLE app_compliance_access_event DROP CONSTRAINT app_compliance_access_event_action_check;
ALTER TABLE app_compliance_access_event ADD CONSTRAINT app_compliance_access_event_action_check
 CHECK (action IN ('MFA_VERIFIED','MFA_DENIED','VIEW_QUEUE','DOWNLOAD_DOCUMENT',
    'VIEW_PLATFORM_SOURCE_READINESS','VIEW_PLATFORM_SOURCE_CONTEXTS',
    'VIEW_PLATFORM_SOURCE_WORK','CREATE_PLATFORM_SOURCE_WORK',
    'UPDATE_PLATFORM_SOURCE_WORK','VIEW_PLATFORM_SOURCE_WORK_HISTORY',
    'VIEW_PLATFORM_SOURCE_INGESTION','SUBMIT_PLATFORM_SOURCE_METADATA',
    'REVIEW_PLATFORM_SOURCE_METADATA','VIEW_PLATFORM_SOURCE_OBJECTS',
    'UPLOAD_PLATFORM_SOURCE_OBJECT','PARSE_PLATFORM_SOURCE_OBJECT',
    'ASSESS_PLATFORM_SOURCE_OBJECT'));
