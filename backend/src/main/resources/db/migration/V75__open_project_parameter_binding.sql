-- CF-OPEN-007 / #96: immutable project pin for one fossil factor, one grid
-- factor, and explicit IR/Dy. A pin is not E8 admission. Licensed factor
-- numbers are not copied. IR and Dy never receive a default of 1.

CREATE TABLE app_open_project_parameter_binding (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 parameter_kind text NOT NULL CHECK (parameter_kind IN ('EF_FUEL','EF_GRID','IR','DY')),
 vintage_year integer NOT NULL CHECK (vintage_year BETWEEN 1900 AND 2100),
 version integer NOT NULL CHECK (version BETWEEN 1 AND 100000000),
 candidate_id uuid,
 source_sha256 bytea,
 normalized_unit text,
 conversion_formula text,
 declared_value numeric(30,18),
 rule_reference text,
 evidence_locator text,
 value_kind text NOT NULL CHECK (value_kind IN ('REFERENCE','DECLARED_ZERO','DECLARED_EXPLICIT')),
 triage_version integer NOT NULL CHECK (triage_version >= 0),
 state text NOT NULL DEFAULT 'PINNED_NOT_ADMITTED'
   CHECK (state = 'PINNED_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (usable_for_e8 = false),
 binding_sha256 bytea NOT NULL CHECK (octet_length(binding_sha256) = 32),
 published_by uuid NOT NULL REFERENCES app_identity(id),
 published_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE (project_id, id),
 UNIQUE (project_id, parameter_kind, vintage_year, version),
 FOREIGN KEY (project_id, organization_id, environment)
   REFERENCES app_project(id, organization_id, environment),
 FOREIGN KEY (candidate_id, organization_id)
   REFERENCES app_open_parameter_candidate(id, organization_id),
 CHECK (
   (parameter_kind IN ('EF_FUEL','EF_GRID')
     AND value_kind = 'REFERENCE'
     AND candidate_id IS NOT NULL
     AND declared_value IS NULL
     AND rule_reference IS NULL
     AND evidence_locator IS NULL
     AND source_sha256 IS NOT NULL
     AND octet_length(source_sha256) = 32
     AND normalized_unit IS NOT NULL
     AND conversion_formula IS NOT NULL)
   OR
   (parameter_kind IN ('IR','DY')
     AND candidate_id IS NULL
     AND declared_value IS NOT NULL
     AND declared_value >= 0
     AND rule_reference IS NOT NULL
     AND length(btrim(rule_reference)) BETWEEN 8 AND 300
     AND evidence_locator IS NOT NULL
     AND length(btrim(evidence_locator)) BETWEEN 3 AND 300
     AND source_sha256 IS NULL
     AND normalized_unit IS NULL
     AND conversion_formula IS NULL
     AND (
       (value_kind = 'DECLARED_ZERO' AND declared_value = 0)
       OR (value_kind = 'DECLARED_EXPLICIT' AND declared_value > 0)
     ))
 )
);
CREATE INDEX app_open_project_parameter_binding_history
 ON app_open_project_parameter_binding(project_id, parameter_kind, version DESC);

CREATE TABLE app_open_project_parameter_binding_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 binding_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
 PRIMARY KEY (actor_id, command_key),
 FOREIGN KEY (project_id, binding_id)
   REFERENCES app_open_project_parameter_binding(project_id, id)
);
CREATE TRIGGER app_open_project_parameter_binding_immutable
 BEFORE UPDATE OR DELETE ON app_open_project_parameter_binding
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_project_parameter_binding_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_project_parameter_binding_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK(action IN (
    'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
    'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
    'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
    'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
    'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
    'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
    'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
    'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
    'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION',
    'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING','SUBMIT_OPEN_MEASUREMENTS',
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD','VIEW_OPEN_PERIOD_ADMISSION',
    'SUBMIT_OPEN_PERIOD_EVIDENCE','REVIEW_OPEN_PERIOD_EVIDENCE',
    'SUBMIT_OPEN_MONITORING_EVENT','REVIEW_OPEN_MONITORING_EVENT',
    'VIEW_OPEN_METHOD_REVIEW','REVIEW_OPEN_METHOD_REVIEW',
    'VIEW_OPEN_E8_ENERGY_CANDIDATE','PREPARE_OPEN_E8_ENERGY_CANDIDATE',
    'VIEW_OPEN_EFEL_CANDIDATE','SUBMIT_OPEN_EFEL_CANDIDATE','REVIEW_OPEN_EFEL_CANDIDATE',
    'VIEW_OPEN_VT0011_WORKBOOK','SUBMIT_OPEN_VT0011_WORKBOOK','REVIEW_OPEN_VT0011_WORKBOOK',
    'VIEW_OPEN_SIMPLE_OM_A1','PREPARE_OPEN_SIMPLE_OM_A1',
    'VIEW_OPEN_E8_BINDING_CANDIDATE','PREPARE_OPEN_E8_BINDING_CANDIDATE',
    'VIEW_OPEN_E8_FLEET_SELECTION',
    'VIEW_OPEN_E8_RUN','RECORD_OPEN_E8_RUN',
    'VIEW_OPEN_PARAMETER_BINDING'
 ));

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
    'VIEW_OPEN_PARAMETER_BINDING_OPTIONS','PIN_OPEN_PARAMETER_BINDING'));
