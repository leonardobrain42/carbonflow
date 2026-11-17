ALTER TABLE pilot_artifact
    ADD COLUMN media_type text NOT NULL DEFAULT 'text/plain' CHECK (media_type IN ('text/plain','application/zip')),
    ADD COLUMN edition text NOT NULL DEFAULT 'LIMPA' CHECK (edition IN ('LIMPA','COMPARADA')),
    DROP CONSTRAINT pilot_artifact_project_id_dossier_id_variant_version_key,
    DROP CONSTRAINT pilot_artifact_purpose_check,
    ADD UNIQUE (project_id,dossier_id,variant,edition,version),
    ADD CHECK (purpose IN ('AUDITORIA_VAL','AUDITORIA_V1','AUDITORIA_V2'));

ALTER TABLE pilot_grant DROP CONSTRAINT pilot_grant_purpose_check,
    ADD CHECK (purpose IN ('AUDITORIA_VAL','AUDITORIA_V1','AUDITORIA_V2'));

CREATE TABLE pilot_document_dossier (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    revision int NOT NULL CHECK (revision > 0),
    row_version int NOT NULL CHECK (row_version > 0),
    state text NOT NULL CHECK (state IN ('RASCUNHO','EM_REVISAO','DEVOLVIDO','CONGELADO')),
    prepared_by text NOT NULL REFERENCES pilot_identity,
    intake_version int NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    prepared_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    command jsonb NOT NULL,
    review jsonb,
    source jsonb NOT NULL,
    PRIMARY KEY (project_id,id,revision),
    FOREIGN KEY (project_id,intake_version) REFERENCES pilot_intake_revision (project_id,version)
);

CREATE TABLE pilot_document_variant (
    project_id text NOT NULL,
    dossier_id uuid NOT NULL,
    revision int NOT NULL,
    exposure text NOT NULL CHECK (exposure IN ('PUBLICA','RESERVADA')),
    edition text NOT NULL CHECK (edition IN ('LIMPA','COMPARADA')),
    artifact_id uuid NOT NULL,
    manifest bytea NOT NULL,
    PRIMARY KEY (project_id,dossier_id,revision,exposure,edition),
    UNIQUE (project_id,artifact_id,revision),
    FOREIGN KEY (project_id,dossier_id,revision) REFERENCES pilot_document_dossier (project_id,id,revision),
    FOREIGN KEY (project_id,artifact_id,revision) REFERENCES pilot_artifact (project_id,id,version)
);

CREATE TABLE pilot_document_export (
    project_id text NOT NULL,
    id uuid NOT NULL,
    dossier_id uuid NOT NULL,
    revision int NOT NULL,
    exposure text NOT NULL,
    edition text NOT NULL,
    actor_id text NOT NULL REFERENCES pilot_identity,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    destination text NOT NULL,
    exported_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,dossier_id,revision,exposure,edition) REFERENCES pilot_document_variant
);

CREATE TABLE pilot_document_receipt (
    project_id text NOT NULL,
    export_id uuid NOT NULL,
    code text NOT NULL,
    destination text NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    body jsonb NOT NULL,
    PRIMARY KEY (project_id,export_id),
    UNIQUE (project_id,destination,code),
    FOREIGN KEY (project_id,export_id) REFERENCES pilot_document_export (project_id,id)
);
