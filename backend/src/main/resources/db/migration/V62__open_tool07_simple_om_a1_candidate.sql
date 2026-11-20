-- CF-OPEN-007 #96 / V62
-- TOOL07 v7.0 Simple OM Option A1: immutable calculation *candidate*.
-- The five-year LCMR screen and plant/fuel arithmetic are not normative
-- source verification, CO2e hourly conversion or E8 parameter admission.
CREATE TABLE app_open_tool07_simple_om_run (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 workbook_id uuid NOT NULL,
 workbook_review_version integer NOT NULL CHECK(workbook_review_version>0),
 workbook_source_sha256 bytea NOT NULL CHECK(octet_length(workbook_source_sha256)=32),
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 tool07_version text NOT NULL DEFAULT '7.0' CHECK(tool07_version='7.0'),
 method text NOT NULL DEFAULT 'SIMPLE_OM_A1' CHECK(method='SIMPLE_OM_A1'),
 grid_system text NOT NULL DEFAULT 'SIN' CHECK(grid_system='SIN'),
 data_year integer NOT NULL CHECK(data_year BETWEEN 1994 AND 2100),
 share_approach text NOT NULL CHECK(share_approach IN
  ('FIVE_YEAR_AVERAGE_SHARES','FIVE_YEAR_AGGREGATE_SHARE')),
 inventory_source_reference text NOT NULL
  CHECK(char_length(btrim(inventory_source_reference)) BETWEEN 15 AND 600),
 inventory_sha256_claimed bytea NOT NULL
  CHECK(octet_length(inventory_sha256_claimed)=32),
 five_year_source_reference text NOT NULL
  CHECK(char_length(btrim(five_year_source_reference)) BETWEEN 15 AND 600),
 five_year_sha256_claimed bytea NOT NULL
  CHECK(octet_length(five_year_sha256_claimed)=32),
 fuel_source_explanation text NOT NULL
  CHECK(char_length(btrim(fuel_source_explanation)) BETWEEN 30 AND 2000),
 system_boundary_note text NOT NULL
  CHECK(char_length(btrim(system_boundary_note)) BETWEEN 30 AND 2000),
 imports_explanation text NOT NULL
  CHECK(char_length(btrim(imports_explanation)) BETWEEN 30 AND 2000),
 lcmr_share numeric(30,24) NOT NULL CHECK(lcmr_share BETWEEN 0 AND 1),
 lcmr_screen_passed boolean NOT NULL,
 included_generation_mwh numeric(30,12) NOT NULL CHECK(included_generation_mwh>0),
 calculated_co2_t numeric(30,12) NOT NULL CHECK(calculated_co2_t>=0),
 illustrative_om_tco2_per_mwh numeric(30,18) NOT NULL
  CHECK(illustrative_om_tco2_per_mwh>=0),
 content_sha256 bytea NOT NULL CHECK(octet_length(content_sha256)=32),
 state text NOT NULL DEFAULT 'PRELIMINARY_NOT_ADMITTED'
   CHECK(state='PRELIMINARY_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE(project_id,id),
 UNIQUE(project_id,workbook_id,version),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,workbook_id)
   REFERENCES app_open_vt0011_case2_workbook(project_id,id),
 FOREIGN KEY(project_id,workbook_id,workbook_review_version)
   REFERENCES app_open_vt0011_case2_review(project_id,workbook_id,version)
);
CREATE INDEX app_open_tool07_simple_om_run_period
 ON app_open_tool07_simple_om_run(project_id,period_code,created_at DESC);

CREATE TABLE app_open_tool07_simple_om_lcmr (
 project_id text NOT NULL,
 run_id uuid NOT NULL,
 data_year integer NOT NULL CHECK(data_year BETWEEN 1990 AND 2100),
 total_grid_mwh numeric(30,12) NOT NULL CHECK(total_grid_mwh>0),
 lcmr_mwh numeric(30,12) NOT NULL CHECK(lcmr_mwh>=0),
 PRIMARY KEY(project_id,run_id,data_year),
 CHECK(lcmr_mwh<=total_grid_mwh),
 FOREIGN KEY(project_id,run_id)
  REFERENCES app_open_tool07_simple_om_run(project_id,id)
);
CREATE TABLE app_open_tool07_simple_om_plant (
 project_id text NOT NULL,
 run_id uuid NOT NULL,
 plant_code text NOT NULL CHECK(plant_code ~ '^[A-Za-z0-9_.-]{1,64}$'),
 net_generation_mwh numeric(30,12) NOT NULL CHECK(net_generation_mwh>0),
 total_co2_t numeric(30,12) NOT NULL CHECK(total_co2_t>=0),
 PRIMARY KEY(project_id,run_id,plant_code),
 FOREIGN KEY(project_id,run_id)
  REFERENCES app_open_tool07_simple_om_run(project_id,id)
);
CREATE TABLE app_open_tool07_simple_om_fuel (
 project_id text NOT NULL,
 run_id uuid NOT NULL,
 plant_code text NOT NULL,
 fuel_code text NOT NULL CHECK(fuel_code ~ '^[A-Za-z0-9_.-]{1,64}$'),
 consumption_tonnes numeric(30,12) NOT NULL CHECK(consumption_tonnes>0),
 ncv_gj_per_tonne numeric(30,12) NOT NULL CHECK(ncv_gj_per_tonne>0),
 ef_tco2_per_gj numeric(30,12) NOT NULL CHECK(ef_tco2_per_gj>=0),
 source_sha256_claimed bytea NOT NULL CHECK(octet_length(source_sha256_claimed)=32),
 PRIMARY KEY(project_id,run_id,plant_code,fuel_code),
 FOREIGN KEY(project_id,run_id,plant_code)
  REFERENCES app_open_tool07_simple_om_plant(project_id,run_id,plant_code)
);
CREATE TABLE app_open_tool07_simple_om_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 run_id uuid NOT NULL,
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,run_id)
  REFERENCES app_open_tool07_simple_om_run(project_id,id)
);
CREATE TRIGGER app_open_tool07_simple_om_run_immutable
 BEFORE UPDATE OR DELETE ON app_open_tool07_simple_om_run
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_tool07_simple_om_lcmr_immutable
 BEFORE UPDATE OR DELETE ON app_open_tool07_simple_om_lcmr
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_tool07_simple_om_plant_immutable
 BEFORE UPDATE OR DELETE ON app_open_tool07_simple_om_plant
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_tool07_simple_om_fuel_immutable
 BEFORE UPDATE OR DELETE ON app_open_tool07_simple_om_fuel
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_tool07_simple_om_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_tool07_simple_om_command
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
    'VIEW_OPEN_SIMPLE_OM_A1','PREPARE_OPEN_SIMPLE_OM_A1'
 ));
