-- CF-OPEN-008 #97: explicit staff-published metadata-only project handoff.
-- NEVER copy licensed EVR stock, AFEC, MPG, ratings or source rows into
-- customer tables. Existing license-revocation triggers purge their owners.
CREATE TABLE app_open_e8_fleet_selection (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 energy_binding_id uuid NOT NULL,
 fleet_snapshot_id uuid NOT NULL,
 rating_snapshot_id uuid NOT NULL,
 observation_year integer NOT NULL CHECK(observation_year BETWEEN 1900 AND 2100),
 cycle text NOT NULL CHECK(cycle IN ('URBAN','HIGHWAY','COMBINED')),
 fleet_source_sha256 bytea NOT NULL CHECK(octet_length(fleet_source_sha256)=32),
 rating_lineage_sha256 bytea NOT NULL CHECK(octet_length(rating_lineage_sha256)=32),
 selection_sha256 bytea NOT NULL CHECK(octet_length(selection_sha256)=32),
 state text NOT NULL DEFAULT 'PREPARATORY_NOT_ADMITTED'
   CHECK(state='PREPARATORY_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 published_by uuid NOT NULL REFERENCES app_identity(id),
 published_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,period_code,version),
 UNIQUE(project_id,period_code,energy_binding_id,rating_snapshot_id),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,energy_binding_id)
   REFERENCES app_open_e8_input_binding_candidate(project_id,id),
 FOREIGN KEY(fleet_snapshot_id,organization_id)
   REFERENCES app_fleet_composition_snapshot(id,organization_id),
 FOREIGN KEY(rating_snapshot_id,organization_id)
   REFERENCES app_vehicle_rating_snapshot(id,organization_id)
);
CREATE INDEX app_open_e8_fleet_selection_history
 ON app_open_e8_fleet_selection(project_id,period_code,version DESC);
CREATE TABLE app_open_e8_fleet_selection_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 selection_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,selection_id)
   REFERENCES app_open_e8_fleet_selection(project_id,id)
);
CREATE TRIGGER app_open_e8_fleet_selection_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_fleet_selection
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_fleet_selection_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_fleet_selection_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

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
    'VIEW_OPEN_E8_FLEET_SELECTION'
 ));

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
    'ASSIGN_OPEN_E8_FLEET_TO_PROJECT','VIEW_OPEN_E8_FLEET_PROJECT_OPTIONS'));
