-- Roteiro #124, vertical BE: o pino EF_FUEL do projeto pode referenciar uma entrada do catálogo de combustíveis (V111),
-- sem copiar o catálogo e sem candidato de arquivo. Aditivo: o caminho por candidato continua igual. Nada é admitido.
DO $$
DECLARE c record;
BEGIN
 FOR c IN SELECT conname FROM pg_constraint
   WHERE conrelid='app_open_project_parameter_binding'::regclass AND contype='c'
     AND (pg_get_constraintdef(oid) LIKE '%value_kind%') LOOP
  EXECUTE format('ALTER TABLE app_open_project_parameter_binding DROP CONSTRAINT %I', c.conname);
 END LOOP;
END $$;
ALTER TABLE app_open_project_parameter_binding
 ADD CONSTRAINT app_open_project_parameter_binding_value_kind_check
 CHECK (value_kind IN ('REFERENCE','CATALOG_REFERENCE','DECLARED_ZERO','DECLARED_EXPLICIT')),
 ADD CONSTRAINT app_open_project_parameter_binding_shape_check CHECK (
   (parameter_kind IN ('EF_FUEL','EF_GRID') AND value_kind = 'REFERENCE' AND candidate_id IS NOT NULL
     AND declared_value IS NULL AND rule_reference IS NULL AND evidence_locator IS NULL
     AND source_sha256 IS NOT NULL AND octet_length(source_sha256) = 32
     AND normalized_unit IS NOT NULL AND conversion_formula IS NOT NULL)
   OR
   (parameter_kind = 'EF_FUEL' AND value_kind = 'CATALOG_REFERENCE' AND candidate_id IS NULL
     AND declared_value IS NULL AND rule_reference IS NULL AND evidence_locator IS NULL
     AND source_sha256 IS NOT NULL AND octet_length(source_sha256) = 32
     AND normalized_unit = 'tCO2e/USgal' AND conversion_formula IS NOT NULL)
   OR
   (parameter_kind IN ('IR','DY') AND candidate_id IS NULL AND declared_value IS NOT NULL AND declared_value >= 0
     AND rule_reference IS NOT NULL AND length(btrim(rule_reference)) BETWEEN 8 AND 300
     AND evidence_locator IS NOT NULL AND length(btrim(evidence_locator)) BETWEEN 3 AND 300
     AND source_sha256 IS NULL AND normalized_unit IS NULL AND conversion_formula IS NULL
     AND ((value_kind = 'DECLARED_ZERO' AND declared_value = 0) OR (value_kind = 'DECLARED_EXPLICIT' AND declared_value > 0))));

CREATE TABLE app_open_efuel_catalog_pin (
 binding_id uuid PRIMARY KEY,
 project_id text NOT NULL,
 entry_id uuid NOT NULL REFERENCES app_fuel_entry(id),
 fuel_id text NOT NULL REFERENCES app_fuel(id),
 boundary text NOT NULL CHECK (boundary = 'CO2_ONLY'),
 emission_scope text NOT NULL CHECK (emission_scope IN ('TTW','WTW','UNSPECIFIED')),
 on_date date NOT NULL,
 source_gas text NOT NULL CHECK (source_gas = 'CO2_FOSSIL'),
 original_value text NOT NULL,
 original_unit text NOT NULL,
 value_tco2_per_usgal numeric(30,18) NOT NULL CHECK (value_tco2_per_usgal > 0),
 gwp_co2 numeric(10,4) NOT NULL CHECK (gwp_co2 = 1),
 review text NOT NULL CHECK (review IN ('INTERNAL_OK','INDEPENDENT_OK')),
 steps jsonb NOT NULL,
 FOREIGN KEY (project_id, binding_id) REFERENCES app_open_project_parameter_binding(project_id, id)
);
CREATE TRIGGER app_open_efuel_catalog_pin_immutable BEFORE UPDATE OR DELETE ON app_open_efuel_catalog_pin
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
