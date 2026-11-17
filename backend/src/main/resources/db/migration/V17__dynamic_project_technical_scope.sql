-- CF-IMP-008: first tenant-scoped E8 adapter, without changing pilot_* data, hashes, or actor namespaces.
ALTER TABLE app_project
    ADD CONSTRAINT app_project_scoped_identity UNIQUE (id, organization_id, environment);

CREATE TABLE app_project_intake_draft_revision (
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (project_id, version),
    FOREIGN KEY (project_id, organization_id, environment)
        REFERENCES app_project(id, organization_id, environment)
);

CREATE TABLE app_project_preliminary_dossier (
    id uuid NOT NULL PRIMARY KEY,
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    revision integer NOT NULL CHECK (revision > 0),
    intake_version integer NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    preview jsonb NOT NULL CHECK (jsonb_typeof(preview) = 'object'),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE (project_id, revision),
    UNIQUE (project_id, intake_version),
    FOREIGN KEY (project_id, organization_id, environment)
        REFERENCES app_project(id, organization_id, environment),
    FOREIGN KEY (project_id, intake_version)
        REFERENCES app_project_intake_draft_revision(project_id, version)
);

CREATE TABLE app_project_technical_command (
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
    action text NOT NULL CHECK (action IN ('SAVE_INTAKE', 'PREPARE_DOSSIER')),
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    fingerprint bytea NOT NULL CHECK (octet_length(fingerprint) = 32),
    result jsonb NOT NULL CHECK (jsonb_typeof(result) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (actor_id, command_key),
    FOREIGN KEY (project_id, organization_id, environment)
        REFERENCES app_project(id, organization_id, environment)
);

-- A denial logs only an opaque attempted-target digest, regardless of whether the project exists.
CREATE TABLE app_project_technical_access_event (
    id uuid PRIMARY KEY,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    target_digest bytea NOT NULL CHECK (octet_length(target_digest) = 32),
    action text NOT NULL CHECK (action IN ('VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES')),
    outcome text NOT NULL CHECK (outcome IN ('PERMITTED','DENIED')),
    code text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE INDEX app_project_technical_access_actor
    ON app_project_technical_access_event(actor_id, recorded_at DESC);
