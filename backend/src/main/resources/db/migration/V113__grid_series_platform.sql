-- Roteiro #124 PR 2 (etapa 6): série horária de fator de emissão do SIN no nível da PLATAFORMA (dado de fonte, não de projeto).
-- Por que não direto na V60: app_open_efel_hourly_candidate é por projeto, limitada a 744 horas, exige um candidato de
-- energia e fixa gas='CO2e'; a planilha do MCTI é nacional, tem o ano inteiro e é de CO2 (não se presume CO2e).
-- A vinculação ao projeto/V60 é passo posterior, explícito e ainda bloqueado.
CREATE TABLE app_grid_series_edition (
 id uuid PRIMARY KEY,
 source_id text NOT NULL,
 edition text NOT NULL,
 license_id uuid NOT NULL REFERENCES app_content_license(id),
 license_decision_version integer NOT NULL,
 license_basis text NOT NULL CHECK (license_basis IN ('OWNER_INTERNAL','INDEPENDENT')),
 workbook_sha256 text NOT NULL CHECK (workbook_sha256 ~ '^[a-f0-9]{64}$'),
 content_sha256 text NOT NULL CHECK (content_sha256 ~ '^[a-f0-9]{64}$'),
 region text NOT NULL DEFAULT 'SIN' CHECK (region = 'SIN'),
 method text NOT NULL DEFAULT 'DISPATCH_ANALYSIS_HOURLY' CHECK (method = 'DISPATCH_ANALYSIS_HOURLY'),
 gas text NOT NULL CHECK (gas = 'CO2'),
 original_unit text NOT NULL CHECK (original_unit IN ('tCO2/MWh','tCO2/kWh','kgCO2/kWh')),
 target_unit text NOT NULL CHECK (target_unit IN ('tCO2/MWh','tCO2/kWh','kgCO2/kWh')),
 data_year integer NOT NULL CHECK (data_year BETWEEN 2006 AND 2100),
 timezone text NOT NULL CHECK (char_length(timezone) BETWEEN 3 AND 60),
 hour_convention text NOT NULL CHECK (hour_convention IN ('HOUR_ENDING_1_24','HOUR_START_LABEL_1_24')),
 time_convention_source text NOT NULL DEFAULT 'DECLARED_BY_PEOPLE' CHECK (time_convention_source = 'DECLARED_BY_PEOPLE'),
 row_count integer NOT NULL CHECK (row_count BETWEEN 1 AND 9000),
 quality jsonb NOT NULL,
 time_issue_count integer NOT NULL CHECK (time_issue_count >= 0),
 state text NOT NULL DEFAULT 'CANDIDATE_NOT_ADMITTED' CHECK (state = 'CANDIDATE_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK (usable_for_e8 = false),
 eligibility_vt0011_tool07 text NOT NULL DEFAULT 'NOT_EVALUATED' CHECK (eligibility_vt0011_tool07 = 'NOT_EVALUATED'),
 imported_by uuid NOT NULL REFERENCES app_identity(id),
 imported_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 FOREIGN KEY (source_id, edition) REFERENCES app_source_version(source_id, edition),
 UNIQUE (source_id, edition, content_sha256, timezone, hour_convention)
);

CREATE TABLE app_grid_series_value (
 edition_id uuid NOT NULL REFERENCES app_grid_series_edition(id),
 month integer NOT NULL CHECK (month BETWEEN 1 AND 12),
 day integer NOT NULL CHECK (day BETWEEN 1 AND 31),
 hour_label integer NOT NULL CHECK (hour_label BETWEEN 1 AND 24),
 cell text NOT NULL,
 value_text text NOT NULL,
 value_original numeric(38,18) NOT NULL CHECK (value_original >= 0),
 value_target numeric(38,18) NOT NULL CHECK (value_target >= 0),
 interval_start_local text NOT NULL,
 interval_start_utc timestamptz,
 interval_end_utc timestamptz,
 time_issue text,
 CHECK ((interval_start_utc IS NULL) = (interval_end_utc IS NULL)),
 CHECK (interval_start_utc IS NOT NULL OR time_issue IS NOT NULL),
 PRIMARY KEY (edition_id, month, day, hour_label)
);

CREATE TABLE app_grid_series_review (
 id uuid PRIMARY KEY,
 edition_id uuid NOT NULL REFERENCES app_grid_series_edition(id),
 basis text NOT NULL CHECK (basis IN ('INDEPENDENT','OWNER_INTERNAL')),
 outcome text NOT NULL CHECK (outcome IN ('CHECKED','RETURNED')),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 decided_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE TRIGGER app_grid_series_edition_immutable BEFORE UPDATE OR DELETE ON app_grid_series_edition
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_grid_series_value_immutable BEFORE UPDATE OR DELETE ON app_grid_series_value
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_grid_series_review_immutable BEFORE UPDATE OR DELETE ON app_grid_series_review
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
    'VIEW_GRID_SERIES','IMPORT_GRID_SERIES','REVIEW_GRID_SERIES'));
