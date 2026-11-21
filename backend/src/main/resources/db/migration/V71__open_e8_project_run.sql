-- #97: immutable project-scoped calculation attempt.
-- A BLOCKED row stores gate codes only. A REVIEWED_INTERNAL row stores the
-- rehearsal memory. Neither row admits a candidate: usable_for_e8 stays false
-- on V59–V64, and can_issue_vcu cannot be true.
CREATE TABLE app_open_e8_run (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 predecessor_id uuid,
 energy_binding_id uuid,
 fleet_selection_id uuid,
 input_sha256 bytea NOT NULL CHECK(octet_length(input_sha256)=32),
 state text NOT NULL CHECK(state IN ('BLOCKED','REVIEWED_INTERNAL')),
 can_issue_vcu boolean NOT NULL DEFAULT false CHECK(can_issue_vcu=false),
 engine text,
 afec text,
 mpg text,
 be_tco2e text,
 pe_tco2e text,
 le_tco2e text,
 dy text,
 er_tco2e text,
 er_display text,
 trace_json jsonb,
 gate_codes text[] NOT NULL,
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,period_code,version),
 CHECK(predecessor_id IS NULL OR predecessor_id<>id),
 CHECK(cardinality(gate_codes) BETWEEN 1 AND 32),
 CHECK(state<>'REVIEWED_INTERNAL' OR
   (energy_binding_id IS NOT NULL AND fleet_selection_id IS NOT NULL)),
 CHECK(
   (state='BLOCKED' AND engine IS NULL AND afec IS NULL AND mpg IS NULL
     AND be_tco2e IS NULL AND pe_tco2e IS NULL AND le_tco2e IS NULL
     AND dy IS NULL AND er_tco2e IS NULL AND er_display IS NULL
     AND trace_json IS NULL)
   OR
   (state='REVIEWED_INTERNAL' AND engine='vm0038-open-grid-arithmetic@1'
     AND afec IS NOT NULL AND char_length(afec) BETWEEN 1 AND 80
     AND mpg IS NOT NULL AND char_length(mpg) BETWEEN 1 AND 80
     AND be_tco2e IS NOT NULL AND char_length(be_tco2e) BETWEEN 1 AND 80
     AND pe_tco2e IS NOT NULL AND char_length(pe_tco2e) BETWEEN 1 AND 80
     AND le_tco2e IS NOT NULL AND char_length(le_tco2e) BETWEEN 1 AND 80
     AND dy IS NOT NULL AND char_length(dy) BETWEEN 1 AND 80
     AND er_tco2e IS NOT NULL AND char_length(er_tco2e) BETWEEN 1 AND 80
     AND er_display IS NOT NULL AND char_length(er_display) BETWEEN 1 AND 80
     AND trace_json IS NOT NULL)),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,energy_binding_id)
   REFERENCES app_open_e8_input_binding_candidate(project_id,id),
 FOREIGN KEY(project_id,fleet_selection_id)
   REFERENCES app_open_e8_fleet_selection(project_id,id),
 FOREIGN KEY(project_id,predecessor_id)
   REFERENCES app_open_e8_run(project_id,id)
);
CREATE INDEX app_open_e8_run_history
 ON app_open_e8_run(project_id,period_code,version DESC);

CREATE TABLE app_open_e8_run_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 run_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,run_id)
   REFERENCES app_open_e8_run(project_id,id)
);
CREATE TRIGGER app_open_e8_run_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_run
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_e8_run_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_run_command
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
    'VIEW_OPEN_E8_RUN','RECORD_OPEN_E8_RUN'
 ));
