-- Roteiro #124, BL-EX-01 (contrato CF-UX-TECH-001-FROTA-BASELINE-EX-ANTE): seleção de frota EX ANTE para o PD, versionada e
-- imutável, sem MR, sem período e sem vínculo de energia. A V64 (seleção monitorada) NÃO é alterada. Nada é admitido.
CREATE TABLE app_open_baseline_fleet_selection (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment='SANDBOX'),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 purpose text NOT NULL DEFAULT 'PD_EX_ANTE' CHECK (purpose='PD_EX_ANTE'),
 reference_date date NOT NULL,
 observation_year integer NOT NULL CHECK (observation_year BETWEEN 1900 AND 2100),
 credit_start date NOT NULL,
 year_rule text NOT NULL CHECK (year_rule='OPEN-BASELINE-FLEET-YEAR@1'),
 fleet_snapshot_id uuid NOT NULL,
 rating_snapshot_id uuid NOT NULL,
 cycle text NOT NULL CHECK (cycle IN ('URBAN','HIGHWAY','COMBINED')),
 fleet_source_sha256 bytea NOT NULL CHECK (octet_length(fleet_source_sha256)=32),
 rating_lineage_sha256 bytea NOT NULL CHECK (octet_length(rating_lineage_sha256)=32),
 selection_sha256 bytea NOT NULL CHECK (octet_length(selection_sha256)=32),
 rationale text NOT NULL CHECK (length(btrim(rationale)) BETWEEN 15 AND 1000),
 state text NOT NULL DEFAULT 'PREPARATORY_NOT_ADMITTED' CHECK (state='PREPARATORY_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (usable_for_e8=false),
 published_by uuid NOT NULL REFERENCES app_identity(id),
 published_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK (observation_year = extract(year FROM reference_date)::int),
 UNIQUE (project_id,id),
 UNIQUE (project_id,version),
 FOREIGN KEY (project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY (fleet_snapshot_id,organization_id) REFERENCES app_fleet_composition_snapshot(id,organization_id),
 FOREIGN KEY (rating_snapshot_id,organization_id) REFERENCES app_vehicle_rating_snapshot(id,organization_id)
);
CREATE INDEX app_open_baseline_fleet_selection_history ON app_open_baseline_fleet_selection(project_id,version DESC);
CREATE TABLE app_open_baseline_fleet_selection_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 selection_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint)=32),
 PRIMARY KEY (actor_id,command_key),
 FOREIGN KEY (project_id,selection_id) REFERENCES app_open_baseline_fleet_selection(project_id,id)
);
CREATE TRIGGER app_open_baseline_fleet_selection_immutable
 BEFORE UPDATE OR DELETE ON app_open_baseline_fleet_selection
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_baseline_fleet_selection_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_baseline_fleet_selection_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

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
    'DECIDE_PLATFORM_FLEET_COVERAGE',
    'VIEW_METHODOLOGY_REGISTRY','REGISTER_METHODOLOGY',
    'VIEW_METHODOLOGY_SOURCES','REGISTER_METHODOLOGY_CONTEXT','REGISTER_METHODOLOGY_NEED',
    'VIEW_CONTENT_LICENSES','SUBMIT_CONTENT_LICENSE','DECIDE_CONTENT_LICENSE',
    'VIEW_FLEET_ALIAS_CANDIDATES','IMPORT_FLEET_ALIAS_CANDIDATES','DECIDE_FLEET_ALIAS_CANDIDATE',
    'CLASSIFY_PLATFORM_FLEET_MODEL',
    'VIEW_FUEL_CATALOG','REGISTER_FUEL','REGISTER_FUEL_ENTRY','REVIEW_FUEL_ENTRY','CONVERT_FUEL_ENTRY',
    'DECLARE_PUBLIC_CONTENT_LICENSE',
    'VIEW_GRID_SERIES','IMPORT_GRID_SERIES','REVIEW_GRID_SERIES',
    'VIEW_OPEN_BASELINE_FLEET_OPTIONS','VIEW_OPEN_BASELINE_FLEET_SELECTIONS','ASSIGN_OPEN_BASELINE_FLEET_EX_ANTE'));
