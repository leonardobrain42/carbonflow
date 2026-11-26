-- Hipótese de trabalho sobre o que o valor horário do MCTI representa (matriz VT0011/TOOL07, R11), enquanto o MCTI/ONS não
-- responde. Não é fato nem admissão: DECLARED_BY_PEOPLE; DISPATCH_TOP = usinas do topo do despacho (TOOL07 ¶63-69, decisão
-- do responsável do produto em 26/11/2026); SYSTEM_AVERAGE = média do sistema (Average OM, ¶70). Linhas antigas: UNDEFINED.
ALTER TABLE app_open_efel_series_binding
 ADD COLUMN definition_hypothesis text NOT NULL DEFAULT 'UNDEFINED'
  CHECK (definition_hypothesis IN ('UNDEFINED','DISPATCH_TOP','SYSTEM_AVERAGE')),
 ADD COLUMN definition_basis text NOT NULL DEFAULT 'DECLARED_BY_PEOPLE' CHECK (definition_basis = 'DECLARED_BY_PEOPLE');
