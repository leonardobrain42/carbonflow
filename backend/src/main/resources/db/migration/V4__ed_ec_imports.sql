CREATE TABLE pilot_import_file (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    sha256 text NOT NULL,
    original_name text NOT NULL,
    source_id text NOT NULL,
    authorization_id text NOT NULL,
    received_by text NOT NULL REFERENCES pilot_identity,
    received_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    bytes bytea NOT NULL CHECK (octet_length(bytes) BETWEEN 1 AND 10000000),
    PRIMARY KEY (project_id, id),
    UNIQUE (project_id, source_id, sha256)
);

CREATE TABLE pilot_import_job (
    project_id text NOT NULL,
    id uuid NOT NULL,
    file_id uuid NOT NULL,
    mapping_id text NOT NULL,
    mapping_version int NOT NULL,
    period text NOT NULL CHECK (period IN ('M1', 'M2')),
    configuration_version int NOT NULL,
    requested_by text NOT NULL REFERENCES pilot_identity,
    created_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    version int NOT NULL DEFAULT 1,
    state text NOT NULL CHECK (state IN ('AGUARDANDO','PROCESSANDO','CONCLUIDO','RECUSADO','FALHA_RECUPERAVEL')),
    total int NOT NULL DEFAULT 0,
    processed int NOT NULL DEFAULT 0,
    columns int,
    message text,
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, file_id) REFERENCES pilot_import_file (project_id, id),
    FOREIGN KEY (project_id, configuration_version) REFERENCES pilot_intake_revision (project_id, version),
    UNIQUE (project_id, file_id, mapping_id, mapping_version, period),
    CHECK (processed BETWEEN 0 AND total)
);

CREATE INDEX pilot_import_job_queue ON pilot_import_job (created_at, id)
    WHERE state IN ('AGUARDANDO','PROCESSANDO');

CREATE TABLE pilot_import_row (
    project_id text NOT NULL,
    id uuid NOT NULL,
    job_id uuid NOT NULL,
    line int NOT NULL CHECK (line > 1),
    original text NOT NULL,
    classification text NOT NULL DEFAULT 'AGUARDANDO',
    reasons jsonb NOT NULL DEFAULT '[]'::jsonb,
    fact jsonb,
    source_fact text,
    physical_hash text,
    kind text,
    channel text,
    vehicle text,
    start_at timestamptz,
    end_at timestamptz,
    related_row_id uuid,
    review_version int NOT NULL DEFAULT 1,
    decision text NOT NULL DEFAULT 'EM_PREVIA'
        CHECK (decision IN ('EM_PREVIA','PROPOSTA','ADMITIDA','REPETIDA','REJEITADA')),
    proposal jsonb,
    resolution jsonb,
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, job_id) REFERENCES pilot_import_job (project_id, id),
    FOREIGN KEY (project_id, related_row_id) REFERENCES pilot_import_row (project_id, id),
    UNIQUE (project_id, job_id, line)
);

CREATE INDEX pilot_import_remaining ON pilot_import_row (project_id, job_id, line)
    WHERE classification = 'AGUARDANDO';
CREATE INDEX pilot_import_source_fact ON pilot_import_row (project_id, source_fact) WHERE fact IS NOT NULL;
CREATE INDEX pilot_import_row_contour ON pilot_import_row (project_id, kind, channel, start_at) WHERE physical_hash IS NOT NULL;
CREATE INDEX pilot_import_row_vehicle ON pilot_import_row (project_id, vehicle, start_at) WHERE kind = 'ED' AND physical_hash IS NOT NULL;
CREATE INDEX pilot_import_physical ON pilot_import_row (project_id, physical_hash) WHERE fact IS NOT NULL;

CREATE TABLE pilot_observation_revision (
    project_id text NOT NULL,
    id uuid NOT NULL,
    version int NOT NULL CHECK (version > 0),
    predecessor int,
    row_id uuid NOT NULL,
    period text NOT NULL CHECK (period IN ('M1','M2')),
    configuration_version int NOT NULL,
    kind text NOT NULL CHECK (kind IN ('ED','EC')),
    local_year int NOT NULL,
    channel text NOT NULL,
    vehicle text,
    start_at timestamptz NOT NULL,
    end_at timestamptz NOT NULL CHECK (end_at > start_at),
    physical_hash text NOT NULL,
    fact jsonb NOT NULL,
    resolution jsonb NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id, id, version),
    UNIQUE (project_id, row_id),
    FOREIGN KEY (project_id, row_id) REFERENCES pilot_import_row (project_id, id),
    FOREIGN KEY (project_id, configuration_version) REFERENCES pilot_intake_revision (project_id, version),
    FOREIGN KEY (project_id, id, predecessor) REFERENCES pilot_observation_revision (project_id, id, version),
    CHECK ((version = 1 AND predecessor IS NULL) OR (version > 1 AND predecessor = version - 1))
);

CREATE TABLE pilot_observation_head (
    project_id text NOT NULL,
    id uuid NOT NULL,
    version int NOT NULL,
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, id, version) REFERENCES pilot_observation_revision (project_id, id, version)
);

CREATE TABLE pilot_observation_alias (
    project_id text NOT NULL,
    source_fact text NOT NULL,
    observation_id uuid NOT NULL,
    PRIMARY KEY (project_id, source_fact),
    FOREIGN KEY (project_id, observation_id) REFERENCES pilot_observation_head (project_id, id)
);

CREATE INDEX pilot_observation_contour ON pilot_observation_revision (project_id, kind, channel, start_at);
CREATE INDEX pilot_observation_vehicle ON pilot_observation_revision (project_id, vehicle, start_at) WHERE kind = 'ED';
CREATE INDEX pilot_observation_year ON pilot_observation_revision (project_id, kind, local_year);
CREATE INDEX pilot_observation_fingerprint ON pilot_observation_revision (project_id, physical_hash);

CREATE TABLE pilot_selection_snapshot (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    period text NOT NULL CHECK (period IN ('M1','M2')),
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    selected_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, id)
);

CREATE TABLE pilot_selection_entry (
    project_id text NOT NULL,
    snapshot_id uuid NOT NULL,
    observation_id uuid NOT NULL,
    observation_version int NOT NULL,
    PRIMARY KEY (project_id, snapshot_id, observation_id),
    FOREIGN KEY (project_id, snapshot_id) REFERENCES pilot_selection_snapshot (project_id, id),
    FOREIGN KEY (project_id, observation_id, observation_version)
        REFERENCES pilot_observation_revision (project_id, id, version)
);
