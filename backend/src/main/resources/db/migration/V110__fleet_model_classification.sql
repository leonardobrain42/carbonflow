-- D19 slice 2: classification of canonical models. A model keeps its declared identity; its CLASSIFICATION (vehicle class
-- and propulsion) is append-only history. The effective classification is the latest row, or the one declared at
-- registration. UNKNOWN stays explicitly pending. Nothing here admits a model into the initial cut (LDV, BEV): the
-- scope is only a label, and downstream rules (FleetCompositionRules) still require LDV and BEV.
-- Other vehicle classes become classifiable ('OTHER'), without being admitted automatically.
ALTER TABLE app_fleet_model DROP CONSTRAINT app_fleet_model_vehicle_class_check;
ALTER TABLE app_fleet_model ADD CONSTRAINT app_fleet_model_vehicle_class_check
 CHECK (vehicle_class IN ('LDV','HDV','OTHER','UNKNOWN'));

CREATE TABLE app_fleet_model_classification (
 model_id uuid NOT NULL,
 organization_id uuid NOT NULL,
 version integer NOT NULL CHECK (version > 0),
 vehicle_class text NOT NULL CHECK (vehicle_class IN ('LDV','HDV','OTHER','UNKNOWN')),
 propulsion text NOT NULL CHECK (propulsion IN ('BEV','PHEV','OTHER','UNKNOWN')),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
 evidence text CHECK (evidence IS NULL OR char_length(btrim(evidence)) BETWEEN 3 AND 1000),
 decided_by uuid NOT NULL REFERENCES app_identity(id),
 decided_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (model_id, version),
 FOREIGN KEY (model_id, organization_id) REFERENCES app_fleet_model(id, organization_id),
 CHECK ((vehicle_class = 'UNKNOWN' AND propulsion = 'UNKNOWN') OR evidence IS NOT NULL)
);
CREATE TRIGGER app_fleet_model_classification_immutable
 BEFORE UPDATE OR DELETE ON app_fleet_model_classification
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

CREATE VIEW app_fleet_model_effective AS
 SELECT m.id, m.organization_id, m.manufacturer, m.model_name, m.variant,
        COALESCE(c.vehicle_class, m.vehicle_class) AS vehicle_class,
        COALESCE(c.propulsion, m.propulsion) AS propulsion,
        m.created_by, m.recorded_at, COALESCE(c.version, 0) AS classification_version
 FROM app_fleet_model m
 LEFT JOIN LATERAL (SELECT k.version, k.vehicle_class, k.propulsion FROM app_fleet_model_classification k
                    WHERE k.model_id = m.id ORDER BY k.version DESC LIMIT 1) c ON true;

ALTER TABLE app_fleet_command DROP CONSTRAINT app_fleet_command_action_check;
ALTER TABLE app_fleet_command ADD CONSTRAINT app_fleet_command_action_check
 CHECK (action IN ('REGISTER_MODEL','MAP_ALIAS','CREATE_SNAPSHOT','DECIDE_NATIONAL_COVERAGE','CLASSIFY_MODEL'));

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
    'CLASSIFY_PLATFORM_FLEET_MODEL'));
