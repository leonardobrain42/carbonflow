CREATE TABLE synthetic_run (
    project_id   text        NOT NULL CHECK (project_id = 'PIL-SYN-BR-001'),
    environment  text        NOT NULL CHECK (environment = 'SIMULACAO'),
    id           uuid        NOT NULL,
    command_key  text        NOT NULL,
    fixture_id   text        NOT NULL,
    fixture_version integer  NOT NULL CHECK (fixture_version > 0),
    created_at   timestamptz NOT NULL,
    snapshot     bytea       NOT NULL,
    report       jsonb       NOT NULL,
    PRIMARY KEY (project_id, environment, id),
    UNIQUE (project_id, environment, command_key)
);
CREATE INDEX synthetic_run_latest ON synthetic_run (project_id, environment, created_at DESC, id DESC);
