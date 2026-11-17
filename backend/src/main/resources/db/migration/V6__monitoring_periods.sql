CREATE TABLE pilot_period_state (
    project_id text NOT NULL REFERENCES pilot_project,
    period text NOT NULL CHECK (period IN ('M1','M2')),
    version int NOT NULL CHECK (version > 0),
    state text NOT NULL CHECK (state IN ('PREVISTO','COLETANDO','EM_CONFERENCIA','COM_LACUNAS','FECHADO')),
    snapshot jsonb NOT NULL,
    PRIMARY KEY (project_id, period, version)
);

CREATE TABLE pilot_period_closure (
    project_id text NOT NULL,
    period text NOT NULL,
    revision int NOT NULL CHECK (revision > 0),
    state_version int NOT NULL,
    run_id uuid NOT NULL,
    selection_id uuid NOT NULL,
    bytes bytea NOT NULL,
    sha256 text NOT NULL,
    PRIMARY KEY (project_id, period, revision),
    UNIQUE (project_id, period, state_version),
    UNIQUE (project_id, run_id),
    UNIQUE (project_id, selection_id),
    FOREIGN KEY (project_id, period, state_version) REFERENCES pilot_period_state,
    FOREIGN KEY (project_id, run_id) REFERENCES pilot_selected_run,
    FOREIGN KEY (project_id, selection_id) REFERENCES pilot_selection_snapshot
);

CREATE TABLE pilot_period_dependent (
    project_id text NOT NULL,
    period text NOT NULL,
    revision int NOT NULL,
    kind text NOT NULL CHECK (kind IN ('PACOTE','DECISAO','ATO_EXTERNO')),
    reference text NOT NULL,
    reference_version int NOT NULL CHECK (reference_version > 0),
    details jsonb NOT NULL,
    PRIMARY KEY (project_id, period, revision, kind, reference, reference_version),
    FOREIGN KEY (project_id, period, revision) REFERENCES pilot_period_closure
);

CREATE TABLE pilot_measured_split (
    project_id text NOT NULL,
    original_row uuid NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    details jsonb NOT NULL,
    PRIMARY KEY (project_id, original_row),
    FOREIGN KEY (project_id, original_row) REFERENCES pilot_import_row
);

CREATE TABLE pilot_measured_segment (
    project_id text NOT NULL,
    segment_row uuid NOT NULL,
    original_row uuid NOT NULL,
    PRIMARY KEY (project_id, segment_row),
    FOREIGN KEY (project_id, segment_row) REFERENCES pilot_import_row,
    FOREIGN KEY (project_id, original_row) REFERENCES pilot_measured_split
);

CREATE INDEX pilot_crossing_readings ON pilot_import_row (project_id, kind, channel, start_at, end_at)
    WHERE reasons @> '[{"code":"LEITURA_SEM_SEGMENTACAO"}]';
