-- CF-IMP-007: receipts preserve idempotent command results without touching pilot_* data.
CREATE TABLE app_project_command (
    identity_id uuid NOT NULL REFERENCES app_identity(id),
    command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
    action text NOT NULL CHECK (action IN ('CREATE', 'PATCH')),
    project_id text NOT NULL REFERENCES app_project(id),
    request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    response jsonb NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    PRIMARY KEY (identity_id, command_key)
);

CREATE INDEX app_project_command_project ON app_project_command(project_id);

CREATE TABLE app_project_event (
    id uuid PRIMARY KEY,
    project_id text NOT NULL REFERENCES app_project(id),
    identity_id uuid NOT NULL REFERENCES app_identity(id),
    action text NOT NULL CHECK (action IN ('CREATED', 'METADATA_UPDATED')),
    version integer NOT NULL CHECK (version > 0),
    occurred_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (project_id, version)
);
