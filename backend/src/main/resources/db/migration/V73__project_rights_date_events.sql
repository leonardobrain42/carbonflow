-- CF-OPEN-010: operational start and VCS crediting start are different events.
-- A document reference on either event is not an admitted proof.
CREATE TABLE app_project_rights_event (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 event_code text NOT NULL CHECK (event_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,40}$'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 kind text NOT NULL CHECK (kind IN ('OPERATIONAL_START','VCS_START')),
 event_on date NOT NULL,
 document_ref text NOT NULL CHECK (length(btrim(document_ref)) BETWEEN 2 AND 300),
 author_id uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (project_id, event_code, version),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment)
);
CREATE TRIGGER app_project_rights_event_immutable
 BEFORE UPDATE OR DELETE ON app_project_rights_event
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_open_applicability_revision
 ADD COLUMN operational_event_code text,
 ADD COLUMN operational_event_on date,
 ADD COLUMN vcs_event_code text,
 ADD COLUMN vcs_event_on date;
