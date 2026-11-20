-- UX-03A: sites (not duplicate chargers) and pinned OPEN project boundary.
CREATE TABLE app_open_site_revision (
 project_id text NOT NULL, organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 site_code text NOT NULL CHECK (site_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 display_name text NOT NULL CHECK (length(btrim(display_name)) BETWEEN 3 AND 120),
 municipality text NOT NULL CHECK (length(btrim(municipality)) BETWEEN 2 AND 100),
 state_code char(2) NOT NULL,
 address text CHECK (length(btrim(address)) BETWEEN 3 AND 200),
 operational_state text NOT NULL CHECK (operational_state IN ('PLANNED','INSTALLED','ACTIVE')),
 planned_on date, commissioned_on date,
 source_reference text CHECK (length(btrim(source_reference)) BETWEEN 3 AND 300),
 recorded_by uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,site_code,version),
 FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
 CHECK (operational_state <> 'ACTIVE' OR commissioned_on IS NOT NULL),
 CHECK (operational_state <> 'PLANNED' OR commissioned_on IS NULL)
);
CREATE TABLE app_open_project_boundary_revision (
 project_id text NOT NULL, organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 recorded_by uuid NOT NULL REFERENCES app_identity(id),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,version),
 FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment)
);
CREATE TABLE app_open_project_boundary_charger (
 project_id text NOT NULL, boundary_version integer NOT NULL,
 charger_code text NOT NULL, charger_version integer NOT NULL,
 site_code text NOT NULL, site_version integer NOT NULL,
 PRIMARY KEY(project_id,boundary_version,charger_code),
 FOREIGN KEY(project_id,boundary_version) REFERENCES app_open_project_boundary_revision(project_id,version),
 FOREIGN KEY(project_id,charger_code,charger_version)
 REFERENCES app_open_charger_revision(project_id,charger_code,version),
 FOREIGN KEY(project_id,site_code,site_version)
 REFERENCES app_open_site_revision(project_id,site_code,version)
);
CREATE TABLE app_open_site_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL REFERENCES app_project(id),
 action text NOT NULL CHECK (action IN ('SAVE_SITE','SAVE_BOUNDARY')),
 target_code text NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 result jsonb NOT NULL CHECK (jsonb_typeof(result)='object'),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(actor_id,command_key)
);
CREATE TRIGGER app_open_site_immutable BEFORE UPDATE OR DELETE ON app_open_site_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_boundary_immutable BEFORE UPDATE OR DELETE ON app_open_project_boundary_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_boundary_selection_immutable BEFORE UPDATE OR DELETE ON app_open_project_boundary_charger
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
-- Planning only: no VVB/Verra approval, rights, measurements, calculations or E8 admission.
