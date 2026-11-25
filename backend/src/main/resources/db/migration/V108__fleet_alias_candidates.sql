-- #94/#95: review queue of alias CANDIDATES between literal SENATRAN names and electric rows of the Inmetro PBEV table.
-- A candidate is evidence for a reviewer, never an alias: the canonical catalog (app_fleet_model, app_fleet_model_alias)
-- is not touched, a decision does not create a model or an alias, and nothing here admits data or enables calculation.
-- Both tables are append-only. One decision per candidate; revisiting a decision is a later, explicit operation.
CREATE TABLE app_fleet_alias_candidate (
 id uuid PRIMARY KEY,
 observation_period text NOT NULL CHECK (observation_period ~ '^[0-9]{4}-(0[1-9]|1[0-2])$'),
 literal_name text NOT NULL CHECK (char_length(btrim(literal_name)) BETWEEN 1 AND 200),
 record_key text NOT NULL CHECK (record_key ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,99}$'),
 level text NOT NULL CHECK (level IN ('EXACT','MODEL_ONLY','AMBIGUOUS')),
 pbev_source_sha256 text NOT NULL CHECK (pbev_source_sha256 ~ '^[a-f0-9]{64}$'),
 matches jsonb NOT NULL CHECK (jsonb_typeof(matches) = 'array' AND jsonb_array_length(matches) BETWEEN 1 AND 50),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 500),
 imported_by uuid NOT NULL REFERENCES app_identity(id),
 imported_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE (literal_name, observation_period, pbev_source_sha256)
);
CREATE TABLE app_fleet_alias_candidate_decision (
 candidate_id uuid PRIMARY KEY REFERENCES app_fleet_alias_candidate(id),
 outcome text NOT NULL CHECK (outcome IN ('ACCEPTED','REJECTED')),
 selected_pbev_line integer CHECK (selected_pbev_line > 0),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
 decided_by uuid NOT NULL REFERENCES app_identity(id),
 decided_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK ((outcome = 'ACCEPTED') = (selected_pbev_line IS NOT NULL))
);
CREATE TRIGGER app_fleet_alias_candidate_immutable
 BEFORE UPDATE OR DELETE ON app_fleet_alias_candidate
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_fleet_alias_candidate_decision_immutable
 BEFORE UPDATE OR DELETE ON app_fleet_alias_candidate_decision
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
    'VIEW_FLEET_ALIAS_CANDIDATES','IMPORT_FLEET_ALIAS_CANDIDATES','DECIDE_FLEET_ALIAS_CANDIDATE'));
