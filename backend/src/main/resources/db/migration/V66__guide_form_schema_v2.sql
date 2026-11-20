-- CF-UX-TECH-001 / UX-02A: versioned presentation/validation of existing preparatory guides.
-- Existing revision JSON and hashes remain unchanged (definition_version=1 for pre-migration revisions).
ALTER TABLE app_technical_guide
    ADD COLUMN definition_version integer NOT NULL DEFAULT 1 CHECK (definition_version > 0);
ALTER TABLE app_technical_guide_revision
    ADD COLUMN definition_version integer NOT NULL DEFAULT 1 CHECK (definition_version > 0);

-- Edition-bound UI schema; texts remain declarations, NOT documents, canonical facts or approved proof.
UPDATE app_technical_guide AS g
SET definition_version = 2,
    fields = (
        SELECT jsonb_agg(
            CASE
                WHEN g.id = 'quantification' AND f.item->>'key' = 'vintage'
                THEN f.item || '{"type":"year","helpText":"Ano de quatro dígitos (1900–2100). Não é um fator admitido nem prova de período."}'::jsonb
                WHEN g.id = 'applicability' AND f.item->>'key' = 'boundary'
                THEN f.item || '{"helpText":"Descrição preparatória. Não cadastra estações ou equipamentos, nem comprova a fronteira; use o inventário canônico e evidências."}'::jsonb
                WHEN g.id = 'applicability' AND f.item->>'key' = 'plannedDate'
                THEN f.item || '{"helpText":"Previsão operacional. Não substitui o evento jurídico e a data de início VCS, sujeitos à cronologia e evidências próprias."}'::jsonb
                WHEN g.id = 'applicability' AND f.item->>'key' = 'startEvidence'
                THEN f.item || '{"helpText":"Contexto apenas: escrever o nome de um documento não o anexa, não cria evento e não confere sua validade."}'::jsonb
                WHEN g.id = 'applicability' AND f.item->>'key' = 'rights'
                THEN f.item || '{"helpText":"Declaração provisória: titularidade, contratos e poderes do proponente dependem de entidades, documentos e revisão próprios."}'::jsonb
                WHEN g.id = 'baseline' AND f.item->>'key' = 'rating'
                THEN f.item || '{"helpText":"Identifique unidade, ciclo e ano. O número digitado aqui não é publicado no cálculo E8 nem substitui o catálogo EVR/PBEV."}'::jsonb
                WHEN g.id = 'additionality' AND f.item->>'key' IN ('considered','decision','commitment')
                THEN f.item || '{"helpText":"Data declarada para análise da cronologia. Comprovação e efeito sobre adicionalidade exigem evento/documento revisado."}'::jsonb
                WHEN g.id = 'monitoring' AND f.item->>'key' = 'calibration'
                THEN f.item || '{"helpText":"Referência preparatória: não substitui o certificado versionado vinculado ao medidor no inventário canônico."}'::jsonb
                ELSE f.item
            END ORDER BY f.ordinal
        )
        FROM jsonb_array_elements(g.fields) WITH ORDINALITY AS f(item, ordinal)
    )
WHERE g.methodology = 'VM0038_V1_1';
