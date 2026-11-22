-- A coverage decision records a human comparison. It does not verify the official
-- national series and does not admit the snapshot for E8.
CREATE TABLE app_fleet_national_coverage_decision (
  id uuid PRIMARY KEY,
  snapshot_id uuid NOT NULL,
  organization_id uuid NOT NULL,
  version integer NOT NULL CHECK (version > 0),
  outcome text NOT NULL CHECK (outcome IN ('NOT_COVERED','NO_OFFICIAL_TOTAL')),
  cited_total bigint CHECK (cited_total IS NULL OR cited_total BETWEEN 1 AND 1000000000000),
  gap_vehicles bigint CHECK (gap_vehicles IS NULL OR gap_vehicles >= 0),
  reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 20 AND 500),
  decided_by uuid NOT NULL REFERENCES app_identity(id),
  decided_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  UNIQUE (snapshot_id, version),
  FOREIGN KEY (snapshot_id, organization_id)
    REFERENCES app_fleet_composition_snapshot(id, organization_id),
  CHECK (
    (outcome = 'NO_OFFICIAL_TOTAL' AND cited_total IS NULL AND gap_vehicles IS NULL)
    OR (outcome = 'NOT_COVERED' AND (
         (cited_total IS NULL AND gap_vehicles IS NULL)
         OR (cited_total IS NOT NULL AND gap_vehicles IS NOT NULL)))
  )
);

CREATE FUNCTION app_fleet_coverage_decision_guard() RETURNS trigger AS $$
DECLARE
  importer uuid;
  denom bigint;
  kind text;
  geo text;
  status text;
BEGIN
  IF TG_OP = 'DELETE' THEN
    RETURN OLD;
  END IF;
  IF TG_OP <> 'INSERT' THEN
    RAISE EXCEPTION 'coverage decision is append-only';
  END IF;
  SELECT i.imported_by, s.denominator, i.data_kind, s.geography, i.status
    INTO importer, denom, kind, geo, status
  FROM app_fleet_composition_snapshot s
  JOIN app_fleet_ingestion i ON i.id = s.ingestion_id
  WHERE s.id = NEW.snapshot_id;
  IF importer IS NULL OR importer = NEW.decided_by
     OR kind <> 'STOCK_IN_CIRCULATION' OR geo <> 'BR'
     OR status <> 'PREPARATORY' OR denom IS NULL OR denom <= 0 THEN
    RAISE EXCEPTION 'coverage decision is not an independent national BEV stock comparison';
  END IF;
  IF EXISTS (
    SELECT 1 FROM app_fleet_ingestion later
    JOIN app_fleet_composition_snapshot s ON s.id = NEW.snapshot_id
    JOIN app_fleet_ingestion current ON current.id = s.ingestion_id
    WHERE later.organization_id = current.organization_id
      AND later.observation_year = current.observation_year
      AND later.geography = current.geography
      AND later.data_kind = current.data_kind
      AND later.imported_at > current.imported_at
      AND later.status = 'PREPARATORY'
  ) THEN
    RAISE EXCEPTION 'coverage decision targets a superseded stock snapshot';
  END IF;
  IF NEW.outcome = 'NOT_COVERED' AND NEW.cited_total IS NOT NULL
     AND (NEW.cited_total <= denom OR NEW.gap_vehicles IS DISTINCT FROM NEW.cited_total - denom) THEN
    RAISE EXCEPTION 'cited national total must exceed the snapshot denominator';
  END IF;
  RETURN NEW;
END;
$$ LANGUAGE plpgsql;

CREATE TRIGGER app_fleet_coverage_decision_guard
BEFORE INSERT OR UPDATE OR DELETE ON app_fleet_national_coverage_decision
FOR EACH ROW EXECUTE FUNCTION app_fleet_coverage_decision_guard();

CREATE OR REPLACE FUNCTION app_fleet_revoke_content() RETURNS trigger AS $$
BEGIN
 IF NEW.outcome IN ('REVOKED','DENIED') THEN
   DELETE FROM app_fleet_national_coverage_decision d
     USING app_fleet_composition_snapshot s, app_fleet_ingestion i
    WHERE d.snapshot_id = s.id AND s.ingestion_id = i.id
      AND i.source_license_id = NEW.license_id;
   DELETE FROM app_fleet_composition_weight w USING app_fleet_composition_snapshot s,
      app_fleet_ingestion i
    WHERE w.snapshot_id=s.id AND s.ingestion_id=i.id AND i.source_license_id=NEW.license_id;
   UPDATE app_fleet_composition_snapshot s
      SET denominator=NULL,number_of_models=NULL,coverage_state='SUSPENDED',rating_state='SUSPENDED',
          purged_at=statement_timestamp()
     FROM app_fleet_ingestion i
     WHERE s.ingestion_id=i.id AND i.source_license_id=NEW.license_id
       AND s.purged_at IS NULL;
   DELETE FROM app_fleet_stock_observation o USING app_fleet_ingestion i
     WHERE o.ingestion_id=i.id AND i.source_license_id=NEW.license_id;
   DELETE FROM app_fleet_sales_observation o USING app_fleet_ingestion i
     WHERE o.ingestion_id=i.id AND i.source_license_id=NEW.license_id;
   UPDATE app_fleet_ingestion SET status='SUSPENDED',purged_at=statement_timestamp()
     WHERE source_license_id=NEW.license_id AND purged_at IS NULL;
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;

ALTER TABLE app_fleet_command DROP CONSTRAINT app_fleet_command_action_check;
ALTER TABLE app_fleet_command ADD CONSTRAINT app_fleet_command_action_check
  CHECK (action IN ('REGISTER_MODEL','MAP_ALIAS','CREATE_SNAPSHOT','DECIDE_NATIONAL_COVERAGE'));

ALTER TABLE app_compliance_access_event DROP CONSTRAINT app_compliance_access_event_action_check;
ALTER TABLE app_compliance_access_event ADD CONSTRAINT app_compliance_access_event_action_check
 CHECK (action IN ('MFA_VERIFIED','MFA_DENIED','VIEW_QUEUE','DOWNLOAD_DOCUMENT',
    'VIEW_PLATFORM_SOURCE_READINESS','VIEW_PLATFORM_SOURCE_CONTEXTS',
    'VIEW_PLATFORM_SOURCE_WORK','CREATE_PLATFORM_SOURCE_WORK',
    'UPDATE_PLATFORM_SOURCE_WORK','VIEW_PLATFORM_SOURCE_WORK_HISTORY',
    'VIEW_PLATFORM_SOURCE_INGESTION','SUBMIT_PLATFORM_SOURCE_METADATA',
    'REVIEW_PLATFORM_SOURCE_METADATA','VIEW_PLATFORM_SOURCE_OBJECTS',
    'UPLOAD_PLATFORM_SOURCE_OBJECT','PARSE_PLATFORM_SOURCE_OBJECT',
    'ASSESS_PLATFORM_SOURCE_OBJECT','VIEW_PLATFORM_SOURCE_CANDIDATES',
    'VIEW_PLATFORM_FLEET','REGISTER_PLATFORM_FLEET_MODEL',
    'MAP_PLATFORM_FLEET_ALIAS','PREVIEW_PLATFORM_FLEET',
    'CREATE_PLATFORM_FLEET_SNAPSHOT','VIEW_VEHICLE_RATINGS',
    'REGISTER_VEHICLE_RATING','PROPOSE_VEHICLE_PAIR','REVIEW_VEHICLE_PAIR',
    'VIEW_VEHICLE_RATING_COMPOSITION','CREATE_VEHICLE_RATING_SNAPSHOT',
    'ASSIGN_OPEN_E8_FLEET_TO_PROJECT','VIEW_OPEN_E8_FLEET_PROJECT_OPTIONS',
    'VIEW_OPEN_FOSSIL_FACTOR','REGISTER_OPEN_FOSSIL_FACTOR','REVIEW_OPEN_FOSSIL_FACTOR',
    'VIEW_OPEN_GRID_FACTOR','REGISTER_OPEN_GRID_FACTOR','REVIEW_OPEN_GRID_FACTOR',
    'VIEW_OPEN_PARAMETER_BINDING_OPTIONS','PIN_OPEN_PARAMETER_BINDING',
    'DECIDE_PLATFORM_FLEET_COVERAGE'));
