-- CF-UX-TECH-001 UX-03B: contextual PDF evidence for canonical station/charger revisions.
-- Reuses the encrypted, SHA256-deduplicated app_project_evidence_blob (V53/V55).
-- A PDF upload and internal review never admit E8, a legal right or a VVB approval.
CREATE TABLE app_open_asset_evidence (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 site_code text,
 site_version integer,
 charger_code text,
 charger_version integer,
 purpose text NOT NULL CHECK(purpose IN
  ('LOCATION','COMMISSIONING','PUBLIC_ACCESS','SPECIFICATION','INSTALLATION','GRID_CONNECTION')),
 evidence_blob_id uuid NOT NULL,
 submitted_file_name text NOT NULL CHECK(submitted_file_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'),
 evidence_reference text NOT NULL CHECK(char_length(btrim(evidence_reference)) BETWEEN 8 AND 300),
 locator text CHECK(locator IS NULL OR char_length(btrim(locator)) BETWEEN 3 AND 160),
 submitted_by uuid NOT NULL REFERENCES app_identity(id),
 submitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK ((site_code IS NOT NULL AND site_version IS NOT NULL AND charger_code IS NULL AND charger_version IS NULL AND
         purpose IN ('LOCATION','COMMISSIONING','PUBLIC_ACCESS'))
     OR (charger_code IS NOT NULL AND charger_version IS NOT NULL AND site_code IS NULL AND site_version IS NULL AND
         purpose IN ('SPECIFICATION','INSTALLATION','GRID_CONNECTION'))),
 UNIQUE(project_id,id),
 FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,site_code,site_version) REFERENCES app_open_site_revision(project_id,site_code,version),
 FOREIGN KEY(project_id,charger_code,charger_version) REFERENCES app_open_charger_revision(project_id,charger_code,version),
 FOREIGN KEY(project_id,evidence_blob_id) REFERENCES app_project_evidence_blob(project_id,id)
);
CREATE UNIQUE INDEX app_open_asset_evidence_site_dedup ON app_open_asset_evidence
 (project_id,site_code,site_version,purpose,evidence_blob_id) WHERE site_code IS NOT NULL;
CREATE UNIQUE INDEX app_open_asset_evidence_charger_dedup ON app_open_asset_evidence
 (project_id,charger_code,charger_version,purpose,evidence_blob_id) WHERE charger_code IS NOT NULL;
CREATE INDEX app_open_asset_evidence_by_project ON app_open_asset_evidence(project_id,submitted_at DESC);
CREATE TABLE app_open_asset_evidence_review (
 project_id text NOT NULL,
 document_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK(outcome IN ('DOCUMENT_CHECKED_INTERNAL','REJECTED')),
 reason text NOT NULL CHECK(char_length(btrim(reason)) BETWEEN 15 AND 2000),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,document_id,version),
 FOREIGN KEY(project_id,document_id) REFERENCES app_open_asset_evidence(project_id,id)
);
CREATE TABLE app_open_asset_evidence_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 document_id uuid NOT NULL,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,document_id) REFERENCES app_open_asset_evidence(project_id,id)
);
CREATE FUNCTION app_open_asset_evidence_review_guard() RETURNS trigger AS $$
BEGIN
 IF EXISTS (SELECT 1 FROM app_open_asset_evidence d
            WHERE d.project_id=NEW.project_id AND d.id=NEW.document_id
              AND d.submitted_by=NEW.reviewed_by) THEN
   RAISE EXCEPTION 'The submitter cannot review their own station/charger proof';
 END IF;
 RETURN NEW;
END; $$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_asset_evidence_review_author_guard BEFORE INSERT ON app_open_asset_evidence_review
 FOR EACH ROW EXECUTE FUNCTION app_open_asset_evidence_review_guard();
CREATE TRIGGER app_open_asset_evidence_immutable BEFORE UPDATE OR DELETE ON app_open_asset_evidence
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_asset_evidence_review_immutable BEFORE UPDATE OR DELETE ON app_open_asset_evidence_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_asset_evidence_command_immutable BEFORE UPDATE OR DELETE ON app_open_asset_evidence_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
