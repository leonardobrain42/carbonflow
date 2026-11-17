CREATE TABLE pilot_verification_activation (
    project_id text NOT NULL,
    verification uuid NOT NULL,
    registry_id text NOT NULL UNIQUE,
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,verification),
    FOREIGN KEY (project_id,verification) REFERENCES pilot_program_request (project_id,id)
);

CREATE TABLE pilot_verification_position (
    project_id text NOT NULL,
    verification uuid NOT NULL,
    id uuid NOT NULL,
    start_on date NOT NULL,
    end_before date NOT NULL CHECK (end_before>start_on),
    as_of timestamptz NOT NULL,
    official_order int CHECK (official_order>=0),
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,verification) REFERENCES pilot_verification_activation
);

CREATE INDEX pilot_position_time ON pilot_verification_position (project_id,verification,start_on,end_before,as_of DESC,official_order DESC);

CREATE TABLE pilot_position_conflict (
    project_id text NOT NULL,
    verification uuid NOT NULL,
    position_id uuid NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id,verification,position_id),
    FOREIGN KEY (project_id,verification) REFERENCES pilot_verification_activation,
    FOREIGN KEY (project_id,position_id) REFERENCES pilot_verification_position (project_id,id)
);

CREATE TABLE pilot_issuance (
    project_id text NOT NULL,
    id uuid NOT NULL,
    verification uuid NOT NULL,
    predecessor uuid,
    external_target text UNIQUE,
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,verification) REFERENCES pilot_verification_activation,
    FOREIGN KEY (project_id,predecessor) REFERENCES pilot_issuance (project_id,id)
);

CREATE INDEX pilot_issuance_by_verification ON pilot_issuance (project_id,verification);
