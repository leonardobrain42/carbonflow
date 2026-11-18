CREATE TABLE app_acquisition_request (
    id uuid PRIMARY KEY,
    project_id text NOT NULL REFERENCES app_project(id),
    section text NOT NULL REFERENCES app_technical_guide(id),
    act text NOT NULL CHECK (act IN ('PD','MR')),
    field text NOT NULL,
    task_id text NOT NULL,
    creator_id uuid NOT NULL REFERENCES app_identity(id),
    UNIQUE(project_id,section,act,field),
    UNIQUE(project_id,id)
);

CREATE TABLE app_acquisition_revision (
    request_id uuid NOT NULL REFERENCES app_acquisition_request(id),
    version integer NOT NULL CHECK (version > 0),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    assignee_id uuid REFERENCES app_identity(id),
    status text NOT NULL CHECK (status IN ('OPEN','UNKNOWN','DECLINED','PARTIAL')),
    plan jsonb NOT NULL CHECK (jsonb_typeof(plan) = 'object'),
    reply jsonb CHECK (reply IS NULL OR jsonb_typeof(reply) = 'object'),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    CHECK ((reply IS NULL) = (status = 'OPEN')),
    PRIMARY KEY(request_id,version)
);
