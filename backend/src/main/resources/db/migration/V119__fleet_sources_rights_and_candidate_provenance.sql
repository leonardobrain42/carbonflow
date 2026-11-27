-- BL-DATA-01C/D (D17: programa/jurisdição + plataforma). Aditiva: metadados técnicos das duas fontes da frota (sem bytes,
-- sem valores) e a proveniência da fonte de frota em cada candidato de alias. Não cria licença nem decisão: o registro
-- dos direitos segue o fluxo existente (declaração interna do responsável + aprovação independente).
-- A edição inclui o prefixo do SHA-256 porque a mesma URL pode ser republicada com outro conteúdo.
INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,sha256,origin)
VALUES ('SENATRAN-RENAVAM-FROTA','2026-09-005963d4','EXTERNAL_SOURCE',
        'Senatran/RENAVAM · Frota por UF, município, marca/modelo e ano de fabricação, setembro de 2026 (agregado, veículos registrados)',
        'Ministério dos Transportes / Senatran',
        'https://dados.transportes.gov.br/dataset/12686da0-3d71-4499-b432-d270f785c907/resource/4408da67-7821-4abf-96db-58acdf1497da/download/i_frota_por_uf_municipio_marca_e_modelo_ano_setembro_2026.zip',
        '005963d4c677c50354bcdb094bf55119beacc25dc644f1d7bfe3611c26fe40a8','EXTERNAL'),
       ('INMETRO-PBEV','2026-rev01-43e928f8','EXTERNAL_SOURCE',
        'Inmetro · Tabela PBE Veicular 2026 (máscara de 19 de janeiro, rev. 01)',
        'Inmetro',
        'https://www.gov.br/inmetro/pt-br/assuntos/regulamentacao/avaliacao-da-conformidade/programa-brasileiro-de-etiquetagem/tabelas-de-eficiencia-energetica/veiculos-automotivos-pbe-veicular/mascara-pbev-2026_19_jan-rev01.pdf/@@download/file',
        '43e928f89a526f7b59013faca3501b2b0716e5a8cdc2e097b8653638aa688ba0','EXTERNAL');

ALTER TABLE app_fleet_alias_candidate
 ADD COLUMN senatran_source_sha256 text CHECK (senatran_source_sha256 ~ '^[a-f0-9]{64}$');
