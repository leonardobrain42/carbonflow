CREATE TABLE pilot_project (
    id text PRIMARY KEY,
    organization_id text NOT NULL,
    environment text NOT NULL CHECK (environment = 'SIMULACAO'),
    UNIQUE (organization_id, id, environment)
);

INSERT INTO pilot_project VALUES
    ('PIL-SYN-BR-001', 'ORG-PILOTO', 'SIMULACAO'),
    ('SIM-PROJETO-INTERNO', 'ORG-PILOTO', 'SIMULACAO'),
    ('SIM-PROJETO-ISOLAMENTO', 'ORG-ISOLAMENTO', 'SIMULACAO');

CREATE TABLE pilot_identity (
    id text PRIMARY KEY,
    organization_id text NOT NULL,
    display_name text NOT NULL,
    password_salt bytea NOT NULL,
    password_hash bytea NOT NULL,
    external_issuer text
);

CREATE TABLE pilot_membership (
    identity_id text NOT NULL REFERENCES pilot_identity,
    project_id text NOT NULL REFERENCES pilot_project,
    roles text[] NOT NULL CHECK (roles <@ ARRAY['PREP','REV','PP','OP','VVB']::text[]),
    valid_from timestamptz NOT NULL,
    valid_until timestamptz NOT NULL CHECK (valid_until > valid_from),
    power_proof text,
    PRIMARY KEY (identity_id, project_id)
);

CREATE TABLE pilot_session (
    token_hash bytea PRIMARY KEY,
    identity_id text NOT NULL REFERENCES pilot_identity,
    expires_at timestamptz NOT NULL
);

ALTER TABLE synthetic_run
    ADD COLUMN organization_id text NOT NULL DEFAULT 'ORG-PILOTO',
    ADD COLUMN actor_id text NOT NULL DEFAULT 'SIM-ENG-LOCAL',
    ADD FOREIGN KEY (organization_id, project_id, environment) REFERENCES pilot_project (organization_id, id, environment);
ALTER TABLE synthetic_run
    DROP CONSTRAINT synthetic_run_project_id_check,
    ALTER COLUMN organization_id DROP DEFAULT,
    ALTER COLUMN actor_id DROP DEFAULT;

CREATE TABLE pilot_artifact (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    dossier_id uuid NOT NULL,
    variant text NOT NULL CHECK (variant IN ('PUBLICA', 'RESERVADA')),
    purpose text NOT NULL CHECK (purpose IN ('AUDITORIA_V1', 'AUDITORIA_V2')),
    title text NOT NULL,
    author_id text NOT NULL REFERENCES pilot_identity,
    content bytea NOT NULL,
    sha256 text NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    approved_by text REFERENCES pilot_identity,
    approved_at timestamptz,
    CHECK ((approved_by IS NULL) = (approved_at IS NULL)),
    CHECK (approved_by IS DISTINCT FROM author_id),
    PRIMARY KEY (project_id, id, version),
    UNIQUE (project_id, dossier_id, variant, version)
);

CREATE TABLE pilot_grant (
    project_id text NOT NULL,
    id uuid NOT NULL,
    artifact_id uuid NOT NULL,
    artifact_version integer NOT NULL,
    purpose text NOT NULL CHECK (purpose IN ('AUDITORIA_V1', 'AUDITORIA_V2')),
    recipient_id text NOT NULL REFERENCES pilot_identity,
    granted_by text NOT NULL REFERENCES pilot_identity,
    granted_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at > granted_at),
    revoked_by text REFERENCES pilot_identity,
    revoked_at timestamptz,
    CHECK ((revoked_by IS NULL) = (revoked_at IS NULL)),
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, artifact_id, artifact_version) REFERENCES pilot_artifact (project_id, id, version)
);

CREATE INDEX pilot_grant_access ON pilot_grant (project_id, recipient_id, artifact_id, artifact_version, purpose);

CREATE TABLE pilot_access_event (
    id uuid PRIMARY KEY,
    project_id text,
    actor_id text REFERENCES pilot_identity,
    action text NOT NULL,
    outcome text NOT NULL CHECK (outcome IN ('PERMITIDO', 'NEGADO')),
    target text,
    occurred_at timestamptz NOT NULL,
    details jsonb NOT NULL
);

CREATE INDEX pilot_access_event_project ON pilot_access_event (project_id, occurred_at DESC);

CREATE TABLE pilot_access_command (
    project_id text NOT NULL REFERENCES pilot_project,
    action text NOT NULL,
    command_key text NOT NULL,
    actor_id text NOT NULL REFERENCES pilot_identity,
    fingerprint text NOT NULL,
    result jsonb NOT NULL,
    PRIMARY KEY (project_id, action, command_key)
);

-- Credenciais das identidades fictícias da demonstração, documentadas no README.
INSERT INTO pilot_identity VALUES
    ('SIM-PREP', 'ORG-PILOTO', 'Preparação', decode('43854a1aa9ae90ec6301dbbafc936b0d', 'hex'), decode('06d8acc628e591434f0da3f6265a1ab6faa3652c920a66f58869304f13ad36a6', 'hex'), NULL),
    ('SIM-REV', 'ORG-PILOTO', 'Revisão interna', decode('80b9c5edd942df3969c30014734aed41', 'hex'), decode('aa313e4bb5408cbe959e3e930257e65bf3bed8d7c2197d9f22d602fcda5e3376', 'hex'), NULL),
    ('SIM-PP', 'ORG-PILOTO', 'Proponente', decode('5ac4cabe215c723ac2287959568c09a1', 'hex'), decode('cc52f3590e85b6d54ca9d91d51183b67ae2d23bad87606985aefc65049566dab', 'hex'), NULL),
    ('SIM-OP', 'ORG-PILOTO', 'Operação', decode('9a4851b82fe8ab8700778948c51284f8', 'hex'), decode('99f604b04ed00f1a511362798ba6338b7a15a75e0f12385567f1b7593d84fd94', 'hex'), NULL),
    ('SIM-VVB', 'ORG-PILOTO', 'Equipe VVB sintética', decode('dadb7e898436cf3c9ee939cd25ddec19', 'hex'), decode('1a8d5db025f46a0200dbdd4a9a86c37877474078b7f40e2419ee3c09a0e1e318', 'hex'), 'SIM-VVB-A'),
    ('SIM-PREP-OUTRA', 'ORG-ISOLAMENTO', 'Preparação · isolamento', decode('ab46e7d9fa49c03c751fefb77f8e8aa6', 'hex'), decode('ef9112427905e641bf364baf53b47a2311b4f81fa873c47b30be78785ea9f4d6', 'hex'), NULL);

INSERT INTO pilot_membership VALUES
    ('SIM-PREP', 'PIL-SYN-BR-001', ARRAY['PREP'], '-infinity', 'infinity', NULL),
    ('SIM-REV', 'PIL-SYN-BR-001', ARRAY['PREP','REV'], '-infinity', 'infinity', NULL),
    ('SIM-PP', 'PIL-SYN-BR-001', ARRAY['PP'], '-infinity', 'infinity', 'SIM-MANDATO-PP-01'),
    ('SIM-OP', 'PIL-SYN-BR-001', ARRAY['OP'], '-infinity', 'infinity', NULL),
    ('SIM-VVB', 'PIL-SYN-BR-001', ARRAY['VVB'], '-infinity', 'infinity', NULL),
    ('SIM-PREP-OUTRA', 'SIM-PROJETO-ISOLAMENTO', ARRAY['PREP'], '-infinity', 'infinity', NULL);

CREATE TABLE pilot_receipt (
    project_id text NOT NULL,
    grant_id uuid NOT NULL,
    issuer text NOT NULL,
    captured_by text NOT NULL REFERENCES pilot_identity,
    captured_at timestamptz NOT NULL,
    PRIMARY KEY (project_id, grant_id),
    FOREIGN KEY (project_id, grant_id) REFERENCES pilot_grant (project_id, id)
);
