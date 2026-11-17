CREATE TABLE pilot_listing (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('DESENVOLVIMENTO','LISTAGEM_VALIDACAO')),
    predecessor uuid,
    version int NOT NULL CHECK (version > 0),
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,predecessor) REFERENCES pilot_listing (project_id,id)
);

CREATE TABLE pilot_listing_receipt (
    project_id text NOT NULL,
    export_id uuid NOT NULL,
    listing_id uuid NOT NULL,
    response jsonb NOT NULL,
    PRIMARY KEY (project_id,export_id),
    FOREIGN KEY (project_id,export_id) REFERENCES pilot_document_receipt,
    FOREIGN KEY (project_id,listing_id) REFERENCES pilot_listing
);

CREATE TABLE pilot_listing_target (
    namespace text NOT NULL,
    target text NOT NULL,
    project_id text NOT NULL,
    listing_id uuid NOT NULL,
    PRIMARY KEY (namespace,target),
    UNIQUE (project_id,listing_id),
    FOREIGN KEY (project_id,listing_id) REFERENCES pilot_listing
);

CREATE TABLE pilot_listing_external_act (
    authority text NOT NULL,
    namespace text NOT NULL,
    official_id text NOT NULL,
    project_id text NOT NULL REFERENCES pilot_project,
    subject uuid NOT NULL,
    digest text NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    response jsonb NOT NULL,
    PRIMARY KEY (authority,namespace,official_id)
);

CREATE TABLE pilot_consultation (
    project_id text NOT NULL,
    id uuid NOT NULL,
    listing_id uuid NOT NULL,
    version int NOT NULL CHECK (version > 0),
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    UNIQUE (project_id,listing_id),
    FOREIGN KEY (project_id,listing_id) REFERENCES pilot_listing
);

CREATE TABLE pilot_consultation_comment (
    project_id text NOT NULL,
    id uuid NOT NULL,
    consultation_id uuid NOT NULL,
    version int NOT NULL CHECK (version > 0),
    state jsonb NOT NULL,
    contact text NOT NULL,
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,consultation_id) REFERENCES pilot_consultation
);

CREATE INDEX pilot_consultation_comments ON pilot_consultation_comment (project_id,consultation_id);
CREATE INDEX pilot_listing_attempts ON pilot_listing (project_id,kind);
