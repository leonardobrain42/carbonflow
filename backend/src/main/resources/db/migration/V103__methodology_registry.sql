-- D18/CF-PACKAGES-001 slice 1: registry of methodologies kept by the platform administration.
-- Metadata only (no source text or bytes). Append-only: a correction is a new version of the same key.
-- Registering a methodology does not create a package, admit data, grant a licence or enable calculation.
CREATE TABLE app_methodology_registry (
 program text NOT NULL CHECK (program ~ '^[A-Z][A-Z0-9_-]{1,39}$'),
 methodology text NOT NULL CHECK (methodology ~ '^[A-Z][A-Z0-9_-]{2,39}$'),
 methodology_edition text NOT NULL CHECK (methodology_edition ~ '^[0-9A-Za-z][0-9A-Za-z._-]{0,19}$'),
 version integer NOT NULL CHECK (version > 0),
 title text NOT NULL CHECK (length(btrim(title)) BETWEEN 3 AND 200),
 rights_holder text NOT NULL CHECK (length(btrim(rights_holder)) BETWEEN 1 AND 200),
 official_uri text CHECK (official_uri IS NULL OR official_uri ~ '^https://[^[:space:]]{4,250}$'),
 origin text NOT NULL CHECK (origin IN ('SEED','ADMIN')),
 registered_by uuid REFERENCES app_identity(id),
 registered_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 digest text NOT NULL CHECK (digest ~ '^[a-f0-9]{64}$'),
 CHECK ((origin = 'SEED') = (registered_by IS NULL)),
 PRIMARY KEY (program, methodology, methodology_edition, version)
);
CREATE TRIGGER app_methodology_registry_immutable
 BEFORE UPDATE OR DELETE ON app_methodology_registry
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

-- The first slice already exists in code and in V37; it is seeded as version 1 so the registry is never empty
-- for it. Digest = sha256 of program|methodology|edition|version|title|rights_holder|official_uri.
INSERT INTO app_methodology_registry(program,methodology,methodology_edition,version,title,rights_holder,
                                     official_uri,origin,digest)
SELECT 'VCS', s.source_id, s.edition, 1, s.title, s.rights_holder, s.official_uri, 'SEED',
       encode(sha256(convert_to('VCS|'||s.source_id||'|'||s.edition||'|1|'||s.title||'|'||s.rights_holder||'|'
                                ||coalesce(s.official_uri,''),'UTF8')),'hex')
FROM app_source_version s
WHERE s.source_id='VM0038' AND s.edition='1.1' AND s.category='METHODOLOGY';

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
    'VIEW_METHODOLOGY_REGISTRY','REGISTER_METHODOLOGY'));
