CREATE TABLE app_source_version (
    source_id text NOT NULL,
    edition text NOT NULL,
    category text NOT NULL CHECK (category IN ('PROGRAM_DOCUMENT','METHODOLOGY','MODULE','TOOL','TEMPLATE','EXTERNAL_SOURCE','PROJECT_DOCUMENT')),
    title text NOT NULL,
    rights_holder text NOT NULL,
    official_uri text,
    sha256 text CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    origin text NOT NULL CHECK (origin IN ('OWN_SYNTHETIC','EXTERNAL','PROJECT')),
    project_id text REFERENCES app_project(id),
    CHECK ((origin = 'PROJECT') = (category = 'PROJECT_DOCUMENT')),
    CHECK ((origin = 'PROJECT') = (project_id IS NOT NULL)),
    PRIMARY KEY (source_id, edition)
);

CREATE TABLE app_content_license (
    id uuid PRIMARY KEY,
    source_id text NOT NULL,
    edition text NOT NULL,
    organization_id uuid REFERENCES app_organization(id),
    project_id text REFERENCES app_project(id),
    actor_id uuid REFERENCES app_identity(id),
    submitted_by uuid REFERENCES app_identity(id),
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    purpose text NOT NULL CHECK (purpose IN ('REQUIREMENT_CATALOG','CONTEXTUAL_HELP','AUTHORIZED_PROCESSING')),
    provider text,
    authorization_ref text NOT NULL CHECK (btrim(authorization_ref) <> ''),
    proof_sha256 text CHECK (proof_sha256 ~ '^[a-f0-9]{64}$'),
    starts_at timestamptz NOT NULL,
    ends_at timestamptz,
    CHECK (ends_at IS NULL OR ends_at > starts_at),
    CHECK (submitted_by IS NOT NULL OR (organization_id IS NULL AND project_id IS NULL AND actor_id IS NULL AND provider IS NULL)),
    FOREIGN KEY (source_id,edition) REFERENCES app_source_version(source_id,edition),
    FOREIGN KEY (project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
    CHECK (project_id IS NULL OR organization_id IS NOT NULL)
);

CREATE TABLE app_content_license_decision (
    license_id uuid NOT NULL REFERENCES app_content_license(id),
    version integer NOT NULL CHECK (version > 0),
    outcome text NOT NULL CHECK (outcome IN ('APPROVED','REVOKED','DENIED','SYNTHETIC_FIXTURE')),
    decided_by uuid REFERENCES app_identity(id),
    decided_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    reason text NOT NULL CHECK (btrim(reason) <> ''),
    CHECK ((outcome = 'SYNTHETIC_FIXTURE') = (decided_by IS NULL)),
    PRIMARY KEY (license_id,version)
);

CREATE TABLE app_content_license_capability (
    license_id uuid NOT NULL,
    decision_version integer NOT NULL,
    operation text NOT NULL CHECK (operation IN ('REFERENCE_REQUIREMENT','STORE_ORIGINAL','EXTRACT_TEXT','INDEX_SEARCH',
        'CREATE_EMBEDDINGS','RAG_RETRIEVE','MODEL_INFERENCE','SHOW_EXCERPTS','DRAFT_CONTENT','EXPORT_DERIVATIVES')),
    PRIMARY KEY (license_id,decision_version,operation),
    FOREIGN KEY (license_id,decision_version) REFERENCES app_content_license_decision(license_id,version)
);
CREATE INDEX app_content_license_source ON app_content_license(source_id,edition);

-- Metadados técnicos. O catálogo não contém bytes ou texto de terceiros.
INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,official_uri,origin)
VALUES ('VM0038','1.1','METHODOLOGY','VM0038 · Sistemas de recarga de veículos elétricos','Verra',
        'https://verra.org/methodologies/vm0038-electric-vehicle-charging-systems-v1-1/','EXTERNAL');

-- Fixture authored for CarbonFlow. The permission is limited to referencing the demo rules.
INSERT INTO app_source_version(source_id,edition,category,title,rights_holder,origin,sha256)
VALUES ('CF-ECA-DEMO','1','EXTERNAL_SOURCE','Requisitos sintéticos de demonstração do CarbonFlow',
        'CarbonFlow','OWN_SYNTHETIC','f5a3466916ca4cd6f740798c0a2350f4632ac139493963a586b70b164ad11617');

INSERT INTO app_content_license(id,source_id,edition,environment,purpose,authorization_ref,starts_at)
VALUES ('76ad5b36-9821-4082-b060-1145172bc134','CF-ECA-DEMO','1','SANDBOX','REQUIREMENT_CATALOG',
        'fixtures/eca-source-v1.txt · autorização interna de demonstração','2020-01-01T00:00:00Z');

INSERT INTO app_content_license_decision(license_id,version,outcome,reason)
VALUES ('76ad5b36-9821-4082-b060-1145172bc134',1,'SYNTHETIC_FIXTURE',
        'Material próprio de teste autorizado para referência no catálogo sintético.');

INSERT INTO app_content_license_capability
VALUES ('76ad5b36-9821-4082-b060-1145172bc134',1,'REFERENCE_REQUIREMENT');
