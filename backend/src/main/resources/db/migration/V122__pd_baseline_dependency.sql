-- Roteiro #124, BL-EX-03 (parte 2, BL-T08/T15): a versão da seção "baseline" do PD fica presa à referência de frota EX ANTE
-- exata (versão e hash da seleção) que existia quando foi redigida. Mesma ideia do pin do MR (V57): proveniência documental,
-- imutável, sem dado licenciado e sem admissão. Aditiva: não altera nenhuma tabela existente.
CREATE TABLE app_pd_baseline_dependency (
 project_id text NOT NULL,
 section_id text NOT NULL CHECK (section_id='baseline'),
 section_version integer NOT NULL,
 selection_version integer NOT NULL CHECK (selection_version BETWEEN 1 AND 100000000),
 selection_sha256 char(64) NOT NULL CHECK (selection_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY (project_id,section_id,section_version),
 FOREIGN KEY (project_id,section_id,section_version)
   REFERENCES app_pd_section_version(project_id,section_id,version),
 FOREIGN KEY (project_id,selection_version)
   REFERENCES app_open_baseline_fleet_selection(project_id,version)
);
CREATE TRIGGER app_pd_baseline_dependency_immutable
 BEFORE UPDATE OR DELETE ON app_pd_baseline_dependency
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
