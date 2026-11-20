-- CF-OPEN-009 / #98, first slice. Project-level configurations only.
-- The pilot's pilot_import_* / pilot_observation_* remain SIMULACAO-only.
-- No measurement observations, energy aggregates, BE/PE/ER or E8 binding are
-- created here: an inventory row is NOT a measured or verified reading.

CREATE TABLE app_open_charger_revision (
  project_id text NOT NULL,
  organization_id uuid NOT NULL,
  environment text NOT NULL CHECK (environment = 'SANDBOX'),
  charger_code text NOT NULL CHECK (charger_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
  version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
  site_code text NOT NULL CHECK (site_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
  charger_kind text NOT NULL CHECK (charger_kind IN ('DCFC','AC','UNKNOWN')),
  rated_power_kw numeric(12,3) CHECK (rated_power_kw > 0 AND rated_power_kw <= 10000),
  grid text NOT NULL CHECK (grid IN ('SIN','OTHER','UNKNOWN')),
  topology text NOT NULL CHECK (topology IN ('GRID_ONLY','ON_SITE','STORAGE','V2G','UNKNOWN')),
  time_zone text NOT NULL CHECK (time_zone IN ('America/Sao_Paulo','UTC','UNKNOWN')),
  source_reference text CHECK (length(btrim(source_reference)) BETWEEN 3 AND 300),
  recorded_by uuid NOT NULL REFERENCES app_identity(id),
  recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY (project_id,charger_code,version),
  FOREIGN KEY(project_id,organization_id,environment)
      REFERENCES app_project(id,organization_id,environment)
);

CREATE INDEX app_open_charger_recent
 ON app_open_charger_revision(project_id,charger_code,version DESC);

CREATE TABLE app_open_meter_configuration_revision (
  project_id text NOT NULL,
  organization_id uuid NOT NULL,
  environment text NOT NULL CHECK (environment = 'SANDBOX'),
  meter_code text NOT NULL CHECK (meter_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,79}$'),
  version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
  charger_code text NOT NULL,
  charger_version integer NOT NULL,
  energy_kind text NOT NULL CHECK (energy_kind IN ('ED','EC')),
  meter_position text NOT NULL CHECK (meter_position IN ('DOWNSTREAM','UPSTREAM')),
  source_system text CHECK (length(btrim(source_system)) BETWEEN 3 AND 200),
  calibration_evidence_ref text CHECK (length(btrim(calibration_evidence_ref)) BETWEEN 3 AND 300),
  calibration_valid_from timestamptz,
  calibration_valid_until timestamptz,
  configured_from timestamptz,
  configured_until timestamptz,
  time_zone text NOT NULL CHECK (time_zone IN ('America/Sao_Paulo','UTC','UNKNOWN')),
  recorded_by uuid NOT NULL REFERENCES app_identity(id),
  recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(project_id,meter_code,version),
  FOREIGN KEY(project_id,organization_id,environment)
      REFERENCES app_project(id,organization_id,environment),
  FOREIGN KEY(project_id,charger_code,charger_version)
      REFERENCES app_open_charger_revision(project_id,charger_code,version),
  CHECK ((energy_kind='ED' AND meter_position='DOWNSTREAM') OR
         (energy_kind='EC' AND meter_position='UPSTREAM')),
  CHECK ((calibration_valid_from IS NULL AND calibration_valid_until IS NULL) OR
         (calibration_valid_from IS NOT NULL AND calibration_valid_until IS NOT NULL
           AND calibration_valid_until > calibration_valid_from)),
  CHECK ((configured_from IS NULL AND configured_until IS NULL) OR
         (configured_from IS NOT NULL AND configured_until IS NOT NULL
           AND configured_until > configured_from)),
  CHECK ((calibration_evidence_ref IS NULL AND calibration_valid_from IS NULL
           AND calibration_valid_until IS NULL) OR calibration_evidence_ref IS NOT NULL)
);

CREATE INDEX app_open_meter_recent
 ON app_open_meter_configuration_revision(project_id,meter_code,version DESC);
CREATE INDEX app_open_meter_charger
 ON app_open_meter_configuration_revision(project_id,charger_code,energy_kind);

CREATE TABLE app_open_monitoring_command (
  actor_id uuid NOT NULL REFERENCES app_identity(id),
  command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
  project_id text NOT NULL REFERENCES app_project(id),
  action text NOT NULL CHECK (action IN ('SAVE_CHARGER','SAVE_METER')),
  target_code text NOT NULL,
  fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
  result jsonb NOT NULL CHECK (jsonb_typeof(result)='object'),
  created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY(actor_id,command_key)
);

-- Revisions cannot be altered in-place. Meter revisions explicitly reference
-- the charger revision they were configured against, enabling STALE checks.
CREATE FUNCTION app_open_metering_immutable() RETURNS trigger AS $$
BEGIN
  RAISE EXCEPTION 'Monitoring configuration is immutable; create a new version';
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER app_open_charger_revision_immutable
 BEFORE UPDATE OR DELETE ON app_open_charger_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_meter_revision_immutable
 BEFORE UPDATE OR DELETE ON app_open_meter_configuration_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK (action IN (
    'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
    'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
    'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
    'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
    'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
    'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
    'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
    'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
    'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION',
    'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING'
 ));
