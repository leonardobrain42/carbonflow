-- CF-IMP-013 / CF-OPEN-010: a project may point at a holder and a contract.
-- The rows are not a legal PP, a grant, or an admitted proof.
CREATE TABLE app_project_rights_party (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 party_code text NOT NULL CHECK (party_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,40}$'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 kind text NOT NULL CHECK (kind IN ('ORGANIZATION','PERSON')),
 display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 2 AND 200),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (project_id, party_code, version),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment)
);
CREATE TABLE app_project_rights_contract (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 contract_code text NOT NULL CHECK (contract_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,40}$'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 party_code text NOT NULL,
 party_version integer NOT NULL,
 label text NOT NULL CHECK (length(btrim(label)) BETWEEN 2 AND 300),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (project_id, contract_code, version),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment),
 FOREIGN KEY (project_id, party_code, party_version)
   REFERENCES app_project_rights_party(project_id, party_code, version)
);
CREATE TABLE app_project_rights_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL,
 project_id text NOT NULL REFERENCES app_project(id),
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
 target_code text NOT NULL,
 revision integer NOT NULL,
 PRIMARY KEY (actor_id, command_key)
);
CREATE TRIGGER app_project_rights_party_immutable
 BEFORE UPDATE OR DELETE ON app_project_rights_party
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_project_rights_contract_immutable
 BEFORE UPDATE OR DELETE ON app_project_rights_contract
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_open_applicability_revision
 ADD COLUMN rights_party_code text,
 ADD COLUMN rights_contract_code text;
