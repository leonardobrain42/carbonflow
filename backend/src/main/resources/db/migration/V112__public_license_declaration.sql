-- Roteiro #124 PR 2 (D19 estendida a licenças): licença pública e declaração interna do responsável.
-- Aditiva. Licença pública (ex.: CC BY-ND 3.0 do site do MCTI) é categoria distinta de autorização particular.
-- A declaração interna (DECLARED_INTERNAL) NÃO é 'APPROVED': nenhum consumidor existente a aceita (todos exigem APPROVED
-- por pessoa distinta de quem submeteu), de modo que ela não simula dupla custódia nem amplia direitos.
ALTER TABLE app_content_license
    ADD COLUMN kind text NOT NULL DEFAULT 'PARTICULAR_AUTHORIZATION' CHECK (kind IN ('PARTICULAR_AUTHORIZATION','PUBLIC_LICENSE')),
    ADD COLUMN license_identifier text,
    ADD COLUMN legal_text_uri text,
    ADD COLUMN legal_text_sha256 text CHECK (legal_text_sha256 ~ '^[a-f0-9]{64}$'),
    ADD COLUMN evidence_uri text,
    ADD COLUMN evidence_captured_at timestamptz,
    ADD COLUMN starts_at_basis text NOT NULL DEFAULT 'DOCUMENT' CHECK (starts_at_basis IN ('DOCUMENT','CAPTURE_DATE','PROVEN_AVAILABILITY')),
    ADD CONSTRAINT app_content_license_kind_fields CHECK (
        (kind = 'PUBLIC_LICENSE' AND license_identifier IS NOT NULL AND legal_text_uri IS NOT NULL
            AND legal_text_sha256 IS NOT NULL AND evidence_uri IS NOT NULL AND evidence_captured_at IS NOT NULL)
        OR (kind = 'PARTICULAR_AUTHORIZATION' AND license_identifier IS NULL AND legal_text_uri IS NULL
            AND legal_text_sha256 IS NULL AND evidence_uri IS NULL AND evidence_captured_at IS NULL));

ALTER TABLE app_content_license_decision DROP CONSTRAINT app_content_license_decision_outcome_check;
ALTER TABLE app_content_license_decision ADD CONSTRAINT app_content_license_decision_outcome_check
    CHECK (outcome IN ('APPROVED','REVOKED','DENIED','SYNTHETIC_FIXTURE','DECLARED_INTERNAL'));

-- Fundamento por operação de uma declaração interna: o que vem da cláusula da licença e o que é interpretação jurídica.
CREATE TABLE app_content_license_declared_ground (
    license_id uuid NOT NULL,
    decision_version integer NOT NULL,
    operation text NOT NULL,
    ground_kind text NOT NULL CHECK (ground_kind IN ('LICENSE_GRANT','LEGAL_INTERPRETATION')),
    grounds text NOT NULL CHECK (char_length(btrim(grounds)) BETWEEN 20 AND 2000),
    PRIMARY KEY (license_id,decision_version,operation),
    FOREIGN KEY (license_id,decision_version,operation) REFERENCES app_content_license_capability(license_id,decision_version,operation)
);
CREATE TRIGGER app_content_license_declared_ground_immutable BEFORE UPDATE OR DELETE ON app_content_license_declared_ground
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
    'DECLARE_PUBLIC_CONTENT_LICENSE'));

-- Metadados técnicos da planilha de Análise de Despacho do MCTI/SIRENE (ano-base 2026, janeiro a outubro). Sem bytes nem
-- valores. A edição inclui o prefixo do SHA-256 porque o MCTI republica arquivos "corrigidos" com o mesmo nome.
INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,sha256,origin)
VALUES ('MCTI-SIN-DESPACHO','2026-janout-7f8e2292','EXTERNAL_SOURCE',
        'MCTI/SIRENE · Fatores de emissão do SIN, Margem de Operação (Análise de Despacho), ano-base 2026 (janeiro a outubro)',
        'Ministério da Ciência, Tecnologia e Inovação',
        'https://www.gov.br/mcti/pt-br/acompanhe-o-mcti/sirene/dados-e-ferramentas/fatores-de-emissao/arquivo/Despacho_2026_janout.xlsx',
        '7f8e2292d12f3c283258ac401b3770042907c39520aaaea1c105589c87f784fe','EXTERNAL');
