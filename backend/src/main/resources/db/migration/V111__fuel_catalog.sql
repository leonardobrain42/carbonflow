-- D19 slice 5 / Q12: persistent, extensible catalog of fuels (platform level, not per organization).
-- Fuel identities, blend compositions and data points (properties, emission factors) with evidence, validity, vintage,
-- gas, scope, GWP set and original unit. NOTHING here admits a factor: admission stays a separate normative act, and
-- registration, internal review (OWNER_INTERNAL) and independent review (INDEPENDENT) are different, append-only facts.
CREATE TABLE app_fuel (
 id text PRIMARY KEY CHECK (id ~ '^[A-Z][A-Z0-9_]{1,59}$'),
 name text NOT NULL CHECK (char_length(btrim(name)) BETWEEN 1 AND 120),
 family text NOT NULL CHECK (family IN ('GASOLINE','ETHANOL','DIESEL','BIODIESEL','NATURAL_GAS','BIOMETHANE','HVO','OTHER')),
 role text NOT NULL CHECK (role IN ('PRODUCT','BLEND')),
 phase text NOT NULL CHECK (phase IN ('LIQUID','GAS')),
 pending text[] NOT NULL DEFAULT '{}',
 created_by uuid REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
INSERT INTO app_fuel(id,name,family,role,phase,pending) VALUES
 ('GASOLINE_A','Gasolina A','GASOLINE','PRODUCT','LIQUID',ARRAY['fator por gás e unidade da metodologia','propriedades (massa específica, PCI) de fonte primária','especificação ANP vigente']),
 ('GASOLINE_C','Gasolina C (comum)','GASOLINE','BLEND','LIQUID',ARRAY['teor anterior à Resolução CNPE nº 9/2026 a partir do texto primário','vigência após os 180 dias e eventual prorrogação']),
 ('ETHANOL_ANHYDROUS','Etanol anidro combustível','ETHANOL','PRODUCT','LIQUID',ARRAY['tratamento do CO2 biogênico pela metodologia','fator por gás e unidade','propriedades de fonte primária']),
 ('ETHANOL_HYDROUS','Etanol hidratado combustível','ETHANOL','PRODUCT','LIQUID',ARRAY['tratamento do CO2 biogênico pela metodologia','fator por gás e unidade','propriedades de fonte primária (divergem do PBEV segundo a nota da AEA)']),
 ('DIESEL_A','Óleo diesel A (mineral)','DIESEL','PRODUCT','LIQUID',ARRAY['fator por gás e unidade','propriedades de fonte primária']),
 ('DIESEL_B','Óleo diesel B (comercial)','DIESEL','BLEND','LIQUID',ARRAY['teor de biodiesel vigente a partir de ato primário (o ato não foi lido)','vigência']),
 ('BIODIESEL_B100','Biodiesel (B100)','BIODIESEL','PRODUCT','LIQUID',ARRAY['tratamento do CO2 biogênico pela metodologia','fator por gás e unidade','propriedades de fonte primária']),
 ('CNG_VEHICULAR','Gás natural veicular (GNV)','NATURAL_GAS','PRODUCT','GAS',ARRAY['composição e variabilidade do gás','fator por gás e unidade','propriedades de fonte primária (divergem do PBEV segundo a nota da AEA)']);

CREATE TABLE app_fuel_entry (
 id uuid PRIMARY KEY,
 kind text NOT NULL CHECK (kind IN ('COMPOSITION','DATUM')),
 fuel_id text NOT NULL REFERENCES app_fuel(id),
 -- composition
 component_id text REFERENCES app_fuel(id),
 composition_basis text CHECK (composition_basis IN ('VOLUME_FRACTION','MASS_FRACTION','ENERGY_FRACTION')),
 nominal numeric(9,8) CHECK (nominal BETWEEN 0 AND 1),
 remainder boolean NOT NULL DEFAULT false,
 -- datum (value kept as published text, with its ORIGINAL unit)
 parameter text CHECK (parameter IN ('EMISSION_FACTOR','DENSITY','LHV')),
 gas text CHECK (gas IN ('CO2_FOSSIL','CO2_BIOGENIC','CO2_UNSPECIFIED','CH4','N2O','CO2E_AGGREGATE')),
 emission_scope text CHECK (emission_scope IN ('TTW','WTW','UNSPECIFIED')),
 gwp_set text CHECK (gwp_set ~ '^AR[4-9]_[0-9]{2,3}$'),
 value_text text CHECK (value_text ~ '^[0-9]+(\.[0-9]+)?$'),
 unit text CHECK (unit ~ '^[A-Za-z0-9]+/[A-Za-z0-9]+$'),
 vintage_year integer CHECK (vintage_year BETWEEN 1990 AND 2100),
 ipcc_reference text CHECK (char_length(ipcc_reference) BETWEEN 3 AND 300),
 ipcc_value text CHECK (ipcc_value ~ '^[0-9]+(\.[0-9]+)?$'),
 ipcc_unit text CHECK (ipcc_unit ~ '^[A-Za-z0-9]+/[A-Za-z0-9]+$'),
 -- common
 valid_from date NOT NULL,
 valid_to date,
 validity_rule text CHECK (char_length(validity_rule) BETWEEN 3 AND 500),
 evidence_source text NOT NULL CHECK (char_length(btrim(evidence_source)) BETWEEN 3 AND 300),
 evidence_locator text NOT NULL CHECK (char_length(btrim(evidence_locator)) BETWEEN 3 AND 300),
 read_state text NOT NULL CHECK (read_state IN ('PRIMARY_READ','SECONDARY')),
 evidence_sha256 text CHECK (evidence_sha256 ~ '^[a-f0-9]{64}$'),
 retrieved_on date,
 registered_by uuid NOT NULL REFERENCES app_identity(id),
 registered_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK (valid_to IS NULL OR valid_to >= valid_from),
 CHECK ((kind = 'COMPOSITION' AND component_id IS NOT NULL AND composition_basis IS NOT NULL
         AND (remainder <> (nominal IS NOT NULL)) AND parameter IS NULL AND value_text IS NULL AND unit IS NULL
         AND gas IS NULL AND emission_scope IS NULL AND gwp_set IS NULL AND vintage_year IS NULL)
     OR (kind = 'DATUM' AND component_id IS NULL AND composition_basis IS NULL AND nominal IS NULL AND NOT remainder
         AND parameter IS NOT NULL AND value_text IS NOT NULL AND unit IS NOT NULL AND vintage_year IS NOT NULL
         AND ((parameter = 'EMISSION_FACTOR' AND gas IS NOT NULL AND emission_scope IS NOT NULL)
              OR (parameter <> 'EMISSION_FACTOR' AND gas IS NULL AND emission_scope IS NULL AND ipcc_value IS NULL))
         AND ((gas = 'CO2E_AGGREGATE') = (gwp_set IS NOT NULL))
         AND ((ipcc_value IS NULL) = (ipcc_unit IS NULL)) AND (ipcc_value IS NULL OR ipcc_reference IS NOT NULL)))
);
CREATE INDEX app_fuel_entry_fuel_idx ON app_fuel_entry(fuel_id, kind, registered_at DESC);

CREATE TABLE app_fuel_entry_review (
 id uuid PRIMARY KEY,
 entry_id uuid NOT NULL REFERENCES app_fuel_entry(id),
 basis text NOT NULL CHECK (basis IN ('INDEPENDENT','OWNER_INTERNAL')),
 outcome text NOT NULL CHECK (outcome IN ('REVIEWED_OK','REJECTED','NEEDS_INFORMATION')),
 reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 10 AND 1000),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 decided_at timestamptz NOT NULL DEFAULT statement_timestamp()
);
CREATE INDEX app_fuel_entry_review_entry_idx ON app_fuel_entry_review(entry_id, decided_at DESC);

CREATE TRIGGER app_fuel_entry_immutable BEFORE UPDATE OR DELETE ON app_fuel_entry
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_fuel_entry_review_immutable BEFORE UPDATE OR DELETE ON app_fuel_entry_review
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
    'VIEW_FUEL_CATALOG','REGISTER_FUEL','REGISTER_FUEL_ENTRY','REVIEW_FUEL_ENTRY','CONVERT_FUEL_ENTRY'));
