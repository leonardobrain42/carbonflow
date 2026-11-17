CREATE TABLE pilot_intake_revision (
    project_id text NOT NULL REFERENCES pilot_project,
    version integer NOT NULL CHECK (version > 0),
    predecessor integer,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    snapshot jsonb NOT NULL,
    PRIMARY KEY (project_id, version),
    FOREIGN KEY (project_id, predecessor) REFERENCES pilot_intake_revision (project_id, version),
    CHECK ((version = 1 AND predecessor IS NULL) OR (version > 1 AND predecessor = version - 1))
);

CREATE TABLE pilot_initial_reading (
    project_id text NOT NULL,
    origin_id text NOT NULL,
    configuration_version integer NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    reading jsonb NOT NULL,
    result jsonb NOT NULL,
    recorded_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id, origin_id),
    FOREIGN KEY (project_id, configuration_version) REFERENCES pilot_intake_revision (project_id, version)
);

CREATE INDEX pilot_initial_reading_recent ON pilot_initial_reading (project_id, recorded_at DESC);
