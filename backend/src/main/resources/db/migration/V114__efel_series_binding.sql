-- Roteiro #124 PR 3: vínculo versionado entre um candidato horário de EFel do projeto (V60) e a série nacional da plataforma
-- (V113). A V60 guarda só as horas que o projeto usa (e continua sendo o dono do cálculo); este vínculo registra de onde
-- vieram, sob qual fronteira de gases e com qual conversão EXPLÍCITA (CO2_ONLY: EF_CO2e = EF_CO2 x GWP(CO2), GWP = 1).
-- Não admite nada: o candidato segue NOT_ADMITTED e o fuso/convenção seguem "declarados por pessoas".
CREATE TABLE app_open_efel_series_binding (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 grid_series_id uuid NOT NULL REFERENCES app_grid_series_edition(id),
 boundary text NOT NULL CHECK (boundary = 'CO2_ONLY'),
 source_gas text NOT NULL CHECK (source_gas = 'CO2'),
 gwp_co2 numeric(10,4) NOT NULL CHECK (gwp_co2 = 1),
 conversion text NOT NULL CHECK (char_length(conversion) BETWEEN 10 AND 300),
 covered_hours integer NOT NULL CHECK (covered_hours BETWEEN 1 AND 744),
 series_content_sha256 text NOT NULL CHECK (series_content_sha256 ~ '^[a-f0-9]{64}$'),
 timezone text NOT NULL,
 hour_convention text NOT NULL,
 time_basis text NOT NULL CHECK (time_basis = 'DECLARED_BY_PEOPLE'),
 license_id uuid NOT NULL REFERENCES app_content_license(id),
 license_basis text NOT NULL CHECK (license_basis IN ('OWNER_INTERNAL','INDEPENDENT')),
 created_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 UNIQUE (project_id, candidate_id),
 FOREIGN KEY (project_id, candidate_id) REFERENCES app_open_efel_hourly_candidate(project_id, id)
);
CREATE TRIGGER app_open_efel_series_binding_immutable BEFORE UPDATE OR DELETE ON app_open_efel_series_binding
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

-- O MCTI publica 16 casas decimais; a V60 guardava no máximo 12 e a série original não pode ser arredondada em silêncio.
-- Ampliação aditiva do valor ORIGINAL (o normalizado já tem 18 casas); linhas existentes permanecem idênticas.
ALTER TABLE app_open_efel_hourly_candidate_value ALTER COLUMN original_value TYPE numeric(30,18);
