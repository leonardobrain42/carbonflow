CREATE TABLE app_evidence_object (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    section text NOT NULL,
    guide_version integer NOT NULL,
    sha256 text NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    usage_scope text NOT NULL DEFAULT 'PROJECT_INTERNAL' CHECK (usage_scope = 'PROJECT_INTERNAL'),
    sensitivity text NOT NULL DEFAULT 'PROJECT_PRIVATE' CHECK (sensitivity = 'PROJECT_PRIVATE'),
    registered_by uuid NOT NULL REFERENCES app_identity(id),
    registered_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE(project_id,section,guide_version),
    UNIQUE(project_id,section,id),
    FOREIGN KEY(project_id,section,guide_version)
        REFERENCES app_technical_guide_revision(project_id,guide_id,version)
);

CREATE TABLE app_fact_candidate (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    section text NOT NULL,
    field text NOT NULL,
    evidence_id uuid NOT NULL,
    request_id uuid,
    request_version integer,
    proposed_by uuid NOT NULL REFERENCES app_identity(id),
    proposed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE(project_id,section,field,evidence_id),
    UNIQUE(project_id,section,field,id),
    CHECK ((request_id IS NULL) = (request_version IS NULL)),
    FOREIGN KEY(project_id,section,evidence_id) REFERENCES app_evidence_object(project_id,section,id),
    FOREIGN KEY(project_id,request_id) REFERENCES app_acquisition_request(project_id,id),
    FOREIGN KEY(request_id,request_version) REFERENCES app_acquisition_revision(request_id,version)
);

CREATE TABLE app_fact_candidate_decision (
    candidate_id uuid PRIMARY KEY REFERENCES app_fact_candidate(id),
    outcome text NOT NULL CHECK (outcome IN ('ACCEPTED','REJECTED')),
    reason text NOT NULL CHECK (btrim(reason) <> ''),
    reviewed_by uuid NOT NULL REFERENCES app_identity(id),
    reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE app_project_fact_version (
    project_id text NOT NULL,
    section text NOT NULL,
    field text NOT NULL,
    version integer NOT NULL CHECK (version > 0),
    candidate_id uuid NOT NULL UNIQUE REFERENCES app_fact_candidate_decision(candidate_id),
    PRIMARY KEY(project_id,section,field,version),
    FOREIGN KEY(project_id,section,field,candidate_id)
        REFERENCES app_fact_candidate(project_id,section,field,id)
);
