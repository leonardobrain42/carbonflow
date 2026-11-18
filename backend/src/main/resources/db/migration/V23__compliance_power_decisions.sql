CREATE TABLE app_compliance_power_decision (
    project_id text NOT NULL,
    request_id uuid NOT NULL,
    submission_version integer NOT NULL DEFAULT 1 CHECK (submission_version = 1),
    version integer NOT NULL CHECK (version > 0),
    decided_by uuid NOT NULL REFERENCES app_identity(id),
    decision text NOT NULL CHECK (decision IN ('NEEDS_INFORMATION','APPROVED','REJECTED','REVOKED')),
    reason text NOT NULL CHECK (char_length(btrim(reason)) BETWEEN 1 AND 600),
    document_id uuid,
    valid_until timestamptz,
    command_key text NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK ((decision = 'APPROVED') = (document_id IS NOT NULL AND valid_until IS NOT NULL)),
    CHECK (decision = 'APPROVED' OR (document_id IS NULL AND valid_until IS NULL)),
    CHECK (valid_until IS NULL OR valid_until > recorded_at),
    PRIMARY KEY (project_id, request_id, version),
    UNIQUE (project_id, request_id, decided_by, command_key),
    FOREIGN KEY (project_id, request_id, submission_version)
      REFERENCES app_project_authority_request_revision(project_id, request_id, version),
    FOREIGN KEY (project_id, request_id, document_id)
      REFERENCES app_compliance_evidence(project_id, request_id, id)
);
