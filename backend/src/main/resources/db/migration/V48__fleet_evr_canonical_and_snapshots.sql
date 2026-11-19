-- CF-OPEN-005: canonical EVR preparatory data. Does NOT publish an E8 project parameter.
-- Staff-only registry: explicit model identity and source alias in a specified year.
CREATE TABLE app_fleet_model (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES app_organization(id),
  manufacturer text NOT NULL CHECK (char_length(btrim(manufacturer)) BETWEEN 2 AND 120),
  model_name text NOT NULL CHECK (char_length(btrim(model_name)) BETWEEN 1 AND 120),
  variant text NOT NULL CHECK (char_length(btrim(variant)) BETWEEN 1 AND 120),
  vehicle_class text NOT NULL CHECK (vehicle_class IN ('LDV','HDV','UNKNOWN')),
  propulsion text NOT NULL CHECK (propulsion IN ('BEV','PHEV','OTHER','UNKNOWN')),
  created_by uuid NOT NULL REFERENCES app_identity(id),
  recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  UNIQUE(id,organization_id),
  UNIQUE(organization_id,manufacturer,model_name,variant,vehicle_class,propulsion)
);

CREATE TABLE app_fleet_model_alias (
  organization_id uuid NOT NULL REFERENCES app_organization(id),
  observation_year integer NOT NULL CHECK (observation_year BETWEEN 1900 AND 2100),
  record_key text NOT NULL CHECK (record_key ~ '^[A-Z0-9][A-Z0-9._-]{0,99}$'),
  model_id uuid NOT NULL,
  mapped_by uuid NOT NULL REFERENCES app_identity(id),
  mapped_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  PRIMARY KEY (organization_id,observation_year,record_key),
  FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id)
);

-- STOCK_IN_CIRCULATION and NEW_SALES are disjoint domains even if published together.
CREATE TABLE app_fleet_ingestion (
  id uuid PRIMARY KEY,
  organization_id uuid NOT NULL REFERENCES app_organization(id),
  source_object_id uuid NOT NULL REFERENCES app_platform_source_object(id),
  source_license_id uuid NOT NULL REFERENCES app_content_license(id),
  source_sha256 bytea NOT NULL CHECK (octet_length(source_sha256)=32),
  data_kind text NOT NULL CHECK (data_kind IN ('STOCK_IN_CIRCULATION','NEW_SALES')),
  observation_year integer NOT NULL CHECK (observation_year BETWEEN 1900 AND 2100),
  geography text NOT NULL CHECK (geography='BR'),
  status text NOT NULL DEFAULT 'PREPARATORY'
    CHECK (status IN ('PREPARATORY','SUSPENDED')),
  imported_by uuid NOT NULL REFERENCES app_identity(id),
  imported_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  purged_at timestamptz,
  UNIQUE(id,organization_id),
  UNIQUE(organization_id,source_object_id,data_kind,observation_year,geography)
);

CREATE TABLE app_fleet_stock_observation (
  ingestion_id uuid NOT NULL REFERENCES app_fleet_ingestion(id),
  organization_id uuid NOT NULL,
  source_row integer NOT NULL CHECK (source_row BETWEEN 2 AND 2001),
  model_id uuid NOT NULL,
  observed_stock bigint NOT NULL CHECK (observed_stock BETWEEN 0 AND 1000000000000),
  record_key text NOT NULL,
  locator text NOT NULL,
  PRIMARY KEY(ingestion_id,source_row),
  UNIQUE(ingestion_id,model_id),
  FOREIGN KEY(ingestion_id,organization_id) REFERENCES app_fleet_ingestion(id,organization_id),
  FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id)
);

CREATE TABLE app_fleet_sales_observation (
  ingestion_id uuid NOT NULL REFERENCES app_fleet_ingestion(id),
  organization_id uuid NOT NULL,
  source_row integer NOT NULL CHECK (source_row BETWEEN 2 AND 2001),
  model_id uuid NOT NULL,
  new_sales bigint NOT NULL CHECK (new_sales BETWEEN 0 AND 1000000000000),
  record_key text NOT NULL,
  locator text NOT NULL,
  PRIMARY KEY(ingestion_id,source_row),
  UNIQUE(ingestion_id,model_id),
  FOREIGN KEY(ingestion_id,organization_id) REFERENCES app_fleet_ingestion(id,organization_id),
  FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id)
);

-- Snapshot numbers immutable on reimport. Purge on rights revocation is an exceptional
-- legal deletion of derived content, retaining only non-numeric audit metadata.
CREATE TABLE app_fleet_composition_snapshot (
  id uuid PRIMARY KEY,
  ingestion_id uuid NOT NULL UNIQUE REFERENCES app_fleet_ingestion(id),
  organization_id uuid NOT NULL REFERENCES app_organization(id),
  observation_year integer NOT NULL,
  geography text NOT NULL DEFAULT 'BR' CHECK (geography='BR'),
  denominator bigint CHECK (denominator>0),
  number_of_models integer NOT NULL CHECK(number_of_models BETWEEN 1 AND 2000),
  coverage_state text NOT NULL DEFAULT 'OFFICIAL_TOTAL_NOT_VERIFIED'
    CHECK (coverage_state IN ('OFFICIAL_TOTAL_NOT_VERIFIED','SUSPENDED')),
  rating_state text NOT NULL DEFAULT 'RATINGS_NOT_LINKED'
    CHECK (rating_state IN ('RATINGS_NOT_LINKED','SUSPENDED')),
  source_sha256 bytea NOT NULL CHECK (octet_length(source_sha256)=32),
  constructed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
  purged_at timestamptz,
  FOREIGN KEY(ingestion_id,organization_id) REFERENCES app_fleet_ingestion(id,organization_id)
);

CREATE TABLE app_fleet_composition_weight (
  snapshot_id uuid NOT NULL REFERENCES app_fleet_composition_snapshot(id),
  organization_id uuid NOT NULL,
  model_id uuid NOT NULL,
  source_row integer NOT NULL,
  observed_stock bigint NOT NULL CHECK (observed_stock BETWEEN 0 AND 1000000000000),
  weight numeric(22,18) NOT NULL CHECK (weight>=0 AND weight<=1),
  source_locator text NOT NULL,
  PRIMARY KEY(snapshot_id,model_id),
  FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id)
);
CREATE INDEX app_fleet_ingestion_scope ON app_fleet_ingestion(organization_id,data_kind,observation_year,imported_at DESC);
CREATE INDEX app_fleet_alias_lookup ON app_fleet_model_alias(organization_id,observation_year);

-- Extra independent guarantee: before the original source candidates are erased, erase
-- all licensed numeric derivatives (both current and historical snapshots).
CREATE FUNCTION app_fleet_revoke_content() RETURNS trigger AS $$
BEGIN
 IF NEW.outcome IN ('REVOKED','DENIED') THEN
   DELETE FROM app_fleet_composition_weight w USING app_fleet_composition_snapshot s,
      app_fleet_ingestion i
    WHERE w.snapshot_id=s.id AND s.ingestion_id=i.id AND i.source_license_id=NEW.license_id;
   UPDATE app_fleet_composition_snapshot s
      SET denominator=NULL,coverage_state='SUSPENDED',rating_state='SUSPENDED',
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
CREATE TRIGGER app_fleet_license_revoked
AFTER INSERT ON app_content_license_decision
FOR EACH ROW EXECUTE FUNCTION app_fleet_revoke_content();

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
    'CREATE_PLATFORM_FLEET_SNAPSHOT'));
