CREATE TABLE app_organization_bootstrap (
    identity_id uuid PRIMARY KEY,
    organization_id uuid NOT NULL UNIQUE,
    event_id uuid NOT NULL UNIQUE,
    command_key text NOT NULL CHECK (command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
    request_fingerprint bytea NOT NULL CHECK (octet_length(request_fingerprint) = 32),
    occurred_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    FOREIGN KEY (organization_id, identity_id) REFERENCES app_organization_membership (organization_id, identity_id)
);
