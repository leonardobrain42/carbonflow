CREATE TABLE pilot_factor_candidate (
    project_id text NOT NULL REFERENCES pilot_project,
    code text NOT NULL,
    version int NOT NULL CHECK (version > 0),
    value jsonb NOT NULL,
    submitted_by text NOT NULL REFERENCES pilot_identity,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id, code, version)
);

CREATE TABLE pilot_factor_review (
    project_id text NOT NULL,
    code text NOT NULL,
    version int NOT NULL,
    reviewed_by text NOT NULL REFERENCES pilot_identity,
    reason text NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id, code, version),
    FOREIGN KEY (project_id, code, version) REFERENCES pilot_factor_candidate (project_id, code, version)
);

CREATE TABLE pilot_selected_run (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    version int NOT NULL DEFAULT 1 CHECK (version > 0),
    period text NOT NULL CHECK (period IN ('M1','M2')),
    selection_id uuid NOT NULL,
    configuration_version int NOT NULL,
    proposed_by text NOT NULL REFERENCES pilot_identity,
    proposed_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    propose_event uuid NOT NULL REFERENCES pilot_access_event,
    reviewed_by text REFERENCES pilot_identity,
    review_event uuid REFERENCES pilot_access_event,
    inputs jsonb NOT NULL,
    state text NOT NULL CHECK (state IN ('PROPOSTA','AGUARDANDO','CALCULADA','COM_LACUNAS','FALHA_RECUPERAVEL')),
    snapshot bytea,
    snapshot_sha256 text,
    result jsonb,
    result_event uuid REFERENCES pilot_access_event,
    PRIMARY KEY (project_id, id),
    FOREIGN KEY (project_id, selection_id) REFERENCES pilot_selection_snapshot (project_id, id),
    FOREIGN KEY (project_id, configuration_version) REFERENCES pilot_intake_revision (project_id, version),
    CHECK (reviewed_by IS DISTINCT FROM proposed_by),
    CHECK ((state IN ('CALCULADA','COM_LACUNAS')) = (snapshot IS NOT NULL AND snapshot_sha256 IS NOT NULL AND result IS NOT NULL))
);
CREATE INDEX pilot_selected_run_queue ON pilot_selected_run (proposed_at, id) WHERE state = 'AGUARDANDO';
CREATE INDEX pilot_selected_run_list ON pilot_selected_run (project_id, proposed_at DESC);
