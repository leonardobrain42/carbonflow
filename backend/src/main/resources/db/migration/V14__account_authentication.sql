ALTER TABLE app_identity ADD COLUMN email_verified_at timestamptz;

CREATE TABLE app_account_credential (
    identity_id uuid PRIMARY KEY REFERENCES app_identity (id),
    password_salt bytea NOT NULL CHECK (octet_length(password_salt) = 16),
    password_hash bytea NOT NULL CHECK (octet_length(password_hash) = 32),
    password_iterations integer NOT NULL CHECK (password_iterations >= 600000)
);

CREATE TABLE app_account_session (
    token_hash bytea PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    identity_id uuid NOT NULL REFERENCES app_identity (id),
    expires_at timestamptz NOT NULL
);

CREATE INDEX app_account_session_identity ON app_account_session (identity_id);

CREATE TABLE app_account_challenge (
    token_hash bytea PRIMARY KEY CHECK (octet_length(token_hash) = 32),
    identity_id uuid NOT NULL REFERENCES app_identity (id),
    purpose text NOT NULL CHECK (purpose IN ('VERIFY_EMAIL', 'RESET_PASSWORD')),
    command_key text NOT NULL,
    expires_at timestamptz NOT NULL,
    superseded_at timestamptz,
    consumed_at timestamptz,
    consumed_key text,
    consumed_fingerprint bytea,
    CHECK ((consumed_at IS NULL) = (consumed_key IS NULL)),
    CHECK ((consumed_at IS NULL) = (consumed_fingerprint IS NULL)),
    CHECK (superseded_at IS NULL OR consumed_at IS NULL),
    UNIQUE (identity_id, purpose, command_key)
);

CREATE UNIQUE INDEX app_account_challenge_current ON app_account_challenge (identity_id, purpose)
    WHERE superseded_at IS NULL AND consumed_at IS NULL;

CREATE TABLE app_account_rate_window (
    bucket_key bytea PRIMARY KEY CHECK (octet_length(bucket_key) = 32),
    window_start timestamptz NOT NULL,
    attempts integer NOT NULL CHECK (attempts > 0)
);

CREATE INDEX app_account_rate_window_expiry ON app_account_rate_window (window_start);

CREATE TABLE app_account_event (
    id uuid PRIMARY KEY,
    identity_id uuid NOT NULL REFERENCES app_identity (id),
    action text NOT NULL,
    occurred_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE INDEX app_account_event_identity ON app_account_event (identity_id, occurred_at);
