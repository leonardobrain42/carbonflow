-- CF-OPEN-003: provenance-first source metadata intake, not document upload or data admission.
-- Immutable review history allows independent metadata acceptance without granting a content license.
CREATE TABLE app_platform_source_submission (
    id uuid PRIMARY KEY,
    need_code text NOT NULL REFERENCES app_methodology_source_need(code),
    source_id text NOT NULL,
    edition text NOT NULL,
    category text NOT NULL CHECK (category IN
        ('PROGRAM_DOCUMENT','METHODOLOGY','MODULE','TOOL','TEMPLATE','EXTERNAL_SOURCE')),
    title text NOT NULL CHECK (char_length(btrim(title)) BETWEEN 3 AND 200),
    rights_holder text NOT NULL CHECK (char_length(btrim(rights_holder)) BETWEEN 2 AND 200),
    publisher text NOT NULL CHECK (char_length(btrim(publisher)) BETWEEN 2 AND 200),
    official_uri text NOT NULL CHECK (official_uri ~ '^https://[^/[:space:]]+'),
    publication_date date,
    reference_year integer CHECK (reference_year BETWEEN 1900 AND 2100),
    locator text NOT NULL CHECK (char_length(btrim(locator)) BETWEEN 2 AND 300),
    declared_original_sha256 text CHECK (declared_original_sha256 ~ '^[a-f0-9]{64}$'),
    -- Metadata fingerprint, NOT a checksum of bytes. Original checksum is unverified until actual ingestion.
    metadata_fingerprint bytea NOT NULL CHECK (octet_length(metadata_fingerprint) = 32),
    state text NOT NULL DEFAULT 'PENDING_REVIEW' CHECK (state IN
        ('PENDING_REVIEW','METADATA_ACCEPTED','NEEDS_CORRECTION','REJECTED')),
    version integer NOT NULL DEFAULT 1 CHECK (version >= 1),
    submitted_by uuid NOT NULL REFERENCES app_identity(id),
    reviewed_by uuid REFERENCES app_identity(id),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE(need_code,metadata_fingerprint)
);
CREATE INDEX app_platform_source_submission_need ON app_platform_source_submission(need_code,created_at DESC);
CREATE INDEX app_platform_source_submission_pending ON app_platform_source_submission(state,created_at DESC);

CREATE TABLE app_platform_source_submission_event (
    submission_id uuid NOT NULL REFERENCES app_platform_source_submission(id),
    version integer NOT NULL CHECK (version >= 1),
    state text NOT NULL CHECK (state IN ('PENDING_REVIEW','METADATA_ACCEPTED','NEEDS_CORRECTION','REJECTED')),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 3 AND 2000),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (submission_id,version)
);

ALTER TABLE app_compliance_access_event DROP CONSTRAINT app_compliance_access_event_action_check;
ALTER TABLE app_compliance_access_event ADD CONSTRAINT app_compliance_access_event_action_check
    CHECK (action IN ('MFA_VERIFIED','MFA_DENIED','VIEW_QUEUE','DOWNLOAD_DOCUMENT',
        'VIEW_PLATFORM_SOURCE_READINESS','VIEW_PLATFORM_SOURCE_CONTEXTS',
        'VIEW_PLATFORM_SOURCE_WORK','CREATE_PLATFORM_SOURCE_WORK',
        'UPDATE_PLATFORM_SOURCE_WORK','VIEW_PLATFORM_SOURCE_WORK_HISTORY',
        'VIEW_PLATFORM_SOURCE_INGESTION','SUBMIT_PLATFORM_SOURCE_METADATA',
        'REVIEW_PLATFORM_SOURCE_METADATA'));
