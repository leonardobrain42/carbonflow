-- CF-OPEN-006 / #95. Candidate PBEV ratings and independent comparability review.
-- No data enters the real E8 project engine from these tables.

CREATE TABLE app_vehicle_rating (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES app_organization(id),
 model_id uuid NOT NULL,
 source_object_id uuid NOT NULL,
 source_license_id uuid NOT NULL REFERENCES app_content_license(id),
 source_row integer NOT NULL CHECK (source_row BETWEEN 2 AND 2001),
 source_sha256 bytea NOT NULL CHECK (octet_length(source_sha256)=32),
 year integer NOT NULL CHECK (year BETWEEN 1900 AND 2100),
 role text NOT NULL CHECK (role IN ('BEV','FOSSIL')),
 fuel text NOT NULL CHECK (fuel IN ('ELECTRICITY','GASOLINE','DIESEL','ETHANOL','FLEX')),
 cycle text NOT NULL CHECK (cycle IN ('URBAN','HIGHWAY','COMBINED')),
 seats integer NOT NULL CHECK (seats BETWEEN 1 AND 80),
 cycle_evidence text NOT NULL CHECK (length(btrim(cycle_evidence)) BETWEEN 5 AND 300),
 original_value numeric(30,12) CHECK (original_value>0),
 original_unit text NOT NULL,
 normalized_value numeric(30,12) CHECK (normalized_value>0),
 normalized_unit text NOT NULL CHECK (normalized_unit IN ('kWh/100mi','mi/USgal')),
 metric text NOT NULL,
 locator text NOT NULL,
 status text NOT NULL DEFAULT 'CANDIDATE' CHECK (status IN ('CANDIDATE','SUSPENDED')),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 purged_at timestamptz,
 UNIQUE(id,organization_id),
 UNIQUE(organization_id,source_object_id,source_row,model_id,cycle),
 FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id),
 FOREIGN KEY(source_object_id,organization_id)
   REFERENCES app_platform_source_object(id,organization_id),
 FOREIGN KEY(source_object_id,source_license_id)
   REFERENCES app_platform_source_object(id,license_id)
);
CREATE INDEX app_vehicle_rating_org ON app_vehicle_rating(organization_id,model_id,year,created_at DESC);
CREATE INDEX app_vehicle_rating_license ON app_vehicle_rating(source_license_id);

CREATE TABLE app_vehicle_comparable_pair (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES app_organization(id),
 fleet_snapshot_id uuid NOT NULL,
 bev_model_id uuid NOT NULL,
 bev_rating_id uuid NOT NULL,
 fossil_rating_id uuid NOT NULL,
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 20 AND 1500),
 service_description text NOT NULL CHECK (length(btrim(service_description)) BETWEEN 10 AND 250),
 status text NOT NULL DEFAULT 'PROPOSED'
  CHECK (status IN ('PROPOSED','ACCEPTED','REJECTED','SUSPENDED')),
 version integer NOT NULL DEFAULT 1 CHECK (version IN (1,2)),
 proposed_by uuid NOT NULL REFERENCES app_identity(id),
 reviewed_by uuid REFERENCES app_identity(id),
 review_reason text,
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 reviewed_at timestamptz,
 UNIQUE(id,organization_id),
 UNIQUE(fleet_snapshot_id,bev_rating_id,fossil_rating_id),
 FOREIGN KEY(fleet_snapshot_id,organization_id)
   REFERENCES app_fleet_composition_snapshot(id,organization_id),
 FOREIGN KEY(bev_rating_id,organization_id) REFERENCES app_vehicle_rating(id,organization_id),
 FOREIGN KEY(fossil_rating_id,organization_id) REFERENCES app_vehicle_rating(id,organization_id),
 FOREIGN KEY(bev_model_id,organization_id) REFERENCES app_fleet_model(id,organization_id),
 CHECK ((version=1 AND reviewed_by IS NULL AND reviewed_at IS NULL)
  OR (version=2 AND reviewed_by IS NOT NULL AND reviewed_at IS NOT NULL))
);
CREATE TABLE app_vehicle_pair_review_event (
 pair_id uuid NOT NULL REFERENCES app_vehicle_comparable_pair(id),
 version integer NOT NULL CHECK (version=2),
 outcome text NOT NULL CHECK (outcome IN ('ACCEPTED','REJECTED')),
 reviewed_by uuid NOT NULL REFERENCES app_identity(id),
 reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 1500),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(pair_id,version)
);

CREATE TABLE app_vehicle_rating_snapshot (
 id uuid PRIMARY KEY,
 organization_id uuid NOT NULL REFERENCES app_organization(id),
 fleet_snapshot_id uuid NOT NULL,
 year integer NOT NULL,
 cycle text NOT NULL CHECK (cycle IN ('URBAN','HIGHWAY','COMBINED')),
 afec_kwh_per_100mi numeric(30,12) CHECK (afec_kwh_per_100mi>0),
 mpg_us numeric(30,12) CHECK (mpg_us>0),
 model_count integer CHECK (model_count BETWEEN 1 AND 2000),
 status text NOT NULL DEFAULT 'PREPARATORY'
  CHECK (status IN ('PREPARATORY','STALE_REFERENCE','SUSPENDED')),
 constructed_by uuid NOT NULL REFERENCES app_identity(id),
 constructed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 purged_at timestamptz,
 FOREIGN KEY(fleet_snapshot_id,organization_id)
  REFERENCES app_fleet_composition_snapshot(id,organization_id),
 UNIQUE(id,organization_id)
);
CREATE TABLE app_vehicle_rating_snapshot_line (
 snapshot_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 model_id uuid NOT NULL,
 pair_id uuid NOT NULL,
 evr bigint NOT NULL CHECK (evr>=0),
 ev_kwh_per_100mi numeric(30,12) NOT NULL CHECK (ev_kwh_per_100mi>0),
 fossil_mpg_us numeric(30,12) NOT NULL CHECK (fossil_mpg_us>0),
 PRIMARY KEY(snapshot_id,model_id),
 FOREIGN KEY(snapshot_id,organization_id)
   REFERENCES app_vehicle_rating_snapshot(id,organization_id),
 FOREIGN KEY(pair_id,organization_id)
   REFERENCES app_vehicle_comparable_pair(id,organization_id),
 FOREIGN KEY(model_id,organization_id) REFERENCES app_fleet_model(id,organization_id)
);

CREATE TABLE app_vehicle_rating_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL,
 action text NOT NULL CHECK (action IN ('REGISTER_RATING','PROPOSE_PAIR','CREATE_RATING_SNAPSHOT')),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 record_id uuid NOT NULL,
 PRIMARY KEY(actor_id,command_key)
);

-- Revocation erases numerical source derivatives. Review outcomes remain audit metadata.
-- Trigger is additional to source quarantine and EVR-stock revocation triggers.
CREATE FUNCTION app_vehicle_rating_revoke_content() RETURNS trigger AS $$
BEGIN
 IF NEW.outcome IN ('REVOKED','DENIED') THEN
   DELETE FROM app_vehicle_rating_snapshot_line l USING app_vehicle_rating_snapshot s
    WHERE l.snapshot_id=s.id AND (
       EXISTS (
         SELECT 1 FROM app_vehicle_comparable_pair p
         JOIN app_vehicle_rating ev ON ev.id=p.bev_rating_id
         JOIN app_vehicle_rating fc ON fc.id=p.fossil_rating_id
         WHERE p.fleet_snapshot_id=s.fleet_snapshot_id
           AND (ev.source_license_id=NEW.license_id OR fc.source_license_id=NEW.license_id)
       )
       OR EXISTS (
         SELECT 1 FROM app_fleet_composition_snapshot f
         JOIN app_fleet_ingestion i ON i.id=f.ingestion_id
         WHERE f.id=s.fleet_snapshot_id AND i.source_license_id=NEW.license_id
       )
    );
   UPDATE app_vehicle_rating_snapshot s SET
       afec_kwh_per_100mi=NULL,mpg_us=NULL,model_count=NULL,
       status='SUSPENDED',purged_at=statement_timestamp()
    WHERE s.purged_at IS NULL AND (
       EXISTS (
         SELECT 1 FROM app_vehicle_comparable_pair p
         JOIN app_vehicle_rating ev ON ev.id=p.bev_rating_id
         JOIN app_vehicle_rating fc ON fc.id=p.fossil_rating_id
         WHERE p.fleet_snapshot_id=s.fleet_snapshot_id
           AND (ev.source_license_id=NEW.license_id OR fc.source_license_id=NEW.license_id)
       )
       OR EXISTS (
         SELECT 1 FROM app_fleet_composition_snapshot f
         JOIN app_fleet_ingestion i ON i.id=f.ingestion_id
         WHERE f.id=s.fleet_snapshot_id AND i.source_license_id=NEW.license_id
       )
    );
   UPDATE app_vehicle_comparable_pair SET status='SUSPENDED'
    WHERE bev_rating_id IN (
      SELECT id FROM app_vehicle_rating WHERE source_license_id=NEW.license_id)
       OR fossil_rating_id IN (
      SELECT id FROM app_vehicle_rating WHERE source_license_id=NEW.license_id);
   UPDATE app_vehicle_rating SET
      original_value=NULL,normalized_value=NULL,status='SUSPENDED',purged_at=statement_timestamp()
    WHERE source_license_id=NEW.license_id AND purged_at IS NULL;
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_vehicle_rating_license_revoked
AFTER INSERT ON app_content_license_decision FOR EACH ROW
 EXECUTE FUNCTION app_vehicle_rating_revoke_content();

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
    'VIEW_VEHICLE_RATING_COMPOSITION','CREATE_VEHICLE_RATING_SNAPSHOT'));
