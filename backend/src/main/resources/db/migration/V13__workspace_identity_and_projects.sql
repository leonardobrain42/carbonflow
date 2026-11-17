CREATE TABLE app_identity (
    id uuid PRIMARY KEY,
    email_normalized text NOT NULL UNIQUE
        CHECK (email_normalized <> '' AND email_normalized = lower(btrim(email_normalized))),
    display_name text NOT NULL CHECK (btrim(display_name) <> ''),
    status text NOT NULL DEFAULT 'PENDING_VERIFICATION'
        CHECK (status IN ('PENDING_VERIFICATION', 'ACTIVE', 'DISABLED')),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp()
);

CREATE TABLE app_organization (
    id uuid PRIMARY KEY,
    environment text NOT NULL CHECK (environment = 'SANDBOX'),
    display_name text NOT NULL CHECK (btrim(display_name) <> ''),
    status text NOT NULL DEFAULT 'PRIVATE_DRAFT' CHECK (status = 'PRIVATE_DRAFT'),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    UNIQUE (id, environment)
);

CREATE TABLE app_organization_membership (
    organization_id uuid NOT NULL REFERENCES app_organization (id),
    identity_id uuid NOT NULL REFERENCES app_identity (id),
    role text NOT NULL CHECK (role = 'DRAFT_OWNER'),
    valid_from timestamptz NOT NULL DEFAULT transaction_timestamp(),
    valid_until timestamptz,
    revoked_at timestamptz,
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    CHECK (valid_until IS NULL OR valid_until > valid_from),
    PRIMARY KEY (organization_id, identity_id)
);

CREATE TABLE app_project (
    id text PRIMARY KEY CHECK (id ~ '^[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}$'),
    organization_id uuid NOT NULL,
    environment text NOT NULL,
    title text NOT NULL CHECK (btrim(title) <> ''),
    description text,
    state text NOT NULL DEFAULT 'DRAFT' CHECK (state = 'DRAFT'),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_by uuid NOT NULL,
    created_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT transaction_timestamp(),
    FOREIGN KEY (organization_id, environment) REFERENCES app_organization (id, environment),
    FOREIGN KEY (organization_id, created_by) REFERENCES app_organization_membership (organization_id, identity_id)
);

CREATE INDEX app_project_owner_directory ON app_project (organization_id, created_by, id);
