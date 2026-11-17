-- A segunda identidade é usada apenas na rotação sintética de M2.
INSERT INTO pilot_identity VALUES
    ('SIM-VVB-B','ORG-PILOTO','Equipe VVB B sintética',decode('ca0aa17cf09843e686c6d494889b01b8','hex'),decode('dd1b6116e15f56b5b51764ace6534562f85d7abc8c7d3275dd92289c8dd47af2','hex'),'SIM-VVB-B');
INSERT INTO pilot_membership VALUES ('SIM-VVB-B','PIL-SYN-BR-001',ARRAY['VVB'],'-infinity','infinity',NULL);

CREATE TABLE pilot_vvb_audit (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('VAL','M1','M2')),
    vvb text NOT NULL REFERENCES pilot_identity,
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    UNIQUE (project_id,kind)
);

CREATE TABLE pilot_program_request (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('REG','V1','V2')),
    audit_id uuid NOT NULL,
    predecessor uuid,
    state jsonb NOT NULL,
    PRIMARY KEY (project_id,id),
    FOREIGN KEY (project_id,audit_id) REFERENCES pilot_vvb_audit (project_id,id),
    FOREIGN KEY (project_id,predecessor) REFERENCES pilot_program_request (project_id,id),
    UNIQUE (project_id,predecessor)
);

CREATE TABLE pilot_program_request_target (
    target text PRIMARY KEY,
    project_id text NOT NULL,
    request_id uuid NOT NULL,
    FOREIGN KEY (project_id,request_id) REFERENCES pilot_program_request (project_id,id)
);

CREATE TABLE pilot_program_request_receipt (
    project_id text NOT NULL,
    export_id uuid NOT NULL,
    request_id uuid NOT NULL,
    PRIMARY KEY (project_id,export_id),
    FOREIGN KEY (project_id,export_id) REFERENCES pilot_document_receipt,
    FOREIGN KEY (project_id,request_id) REFERENCES pilot_program_request (project_id,id)
);
