CREATE TABLE pilot_job_attempt (
    project_id text NOT NULL REFERENCES pilot_project,
    kind text NOT NULL CHECK (kind IN ('IMPORTACAO','CALCULO')),
    job_id uuid NOT NULL,
    generation integer NOT NULL CHECK (generation>0),
    job_version integer NOT NULL CHECK (job_version>0),
    state text NOT NULL CHECK (state IN ('EM_EXECUCAO','CONFIRMADA','EXPIRADA','FALHA_RECUPERAVEL')),
    claimed_at timestamptz NOT NULL,
    expires_at timestamptz NOT NULL CHECK (expires_at>claimed_at),
    finished_at timestamptz,
    result_version integer,
    reason text,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id,kind,job_id,generation),
    CHECK ((state='EM_EXECUCAO')=(finished_at IS NULL))
);

CREATE UNIQUE INDEX pilot_job_one_claim ON pilot_job_attempt (project_id,kind,job_id) WHERE state='EM_EXECUCAO';

CREATE TABLE pilot_credit_end_revision (
    project_id text NOT NULL REFERENCES pilot_project,
    version int NOT NULL CHECK (version>0),
    ends_on date NOT NULL,
    reason text NOT NULL,
    evidence jsonb NOT NULL,
    event_id uuid NOT NULL REFERENCES pilot_access_event,
    PRIMARY KEY (project_id,version)
);

CREATE TABLE pilot_recovery_case (
    project_id text NOT NULL REFERENCES pilot_project,
    id uuid NOT NULL,
    manifest_sha text NOT NULL CHECK (manifest_sha ~ '^[0-9a-f]{64}$'),
    checkpoint timestamptz NOT NULL,
    observed_through timestamptz NOT NULL CHECK (observed_through>=checkpoint),
    gap_sha text NOT NULL CHECK (gap_sha ~ '^[0-9a-f]{64}$'),
    incident_at timestamptz NOT NULL CHECK (incident_at>=checkpoint),
    opened_at timestamptz NOT NULL DEFAULT clock_timestamp(),
    checked_by text REFERENCES pilot_identity,
    check_event uuid REFERENCES pilot_access_event,
    release_event uuid REFERENCES pilot_access_event,
    released_at timestamptz,
    PRIMARY KEY (project_id,id),
    CHECK ((checked_by IS NULL)=(check_event IS NULL)),
    CHECK ((release_event IS NULL)=(released_at IS NULL)),
    CHECK (release_event IS NULL OR check_event IS NOT NULL)
);

CREATE UNIQUE INDEX pilot_one_open_recovery ON pilot_recovery_case (project_id) WHERE release_event IS NULL;

CREATE TABLE pilot_recovery_run (
    project_id text NOT NULL,
    recovery uuid NOT NULL,
    run_id uuid NOT NULL,
    snapshot_sha text NOT NULL CHECK (snapshot_sha ~ '^[0-9a-f]{64}$'),
    PRIMARY KEY (project_id,recovery,run_id),
    FOREIGN KEY (project_id,recovery) REFERENCES pilot_recovery_case (project_id,id),
    FOREIGN KEY (project_id,run_id) REFERENCES pilot_selected_run (project_id,id)
);

CREATE TABLE pilot_recovery_effect (
    project_id text NOT NULL,
    recovery uuid NOT NULL,
    kind text NOT NULL CHECK (kind IN ('ATO_EXTERNO','BRUTO','RECIBO','FATO_ED_EC')),
    source text NOT NULL,
    namespace text NOT NULL,
    reference text NOT NULL,
    digest text NOT NULL CHECK (digest ~ '^[0-9a-f]{64}$'),
    binding jsonb CHECK ((namespace='SIM-RECIBOS-EMISSAO')=(binding IS NOT NULL)),
    PRIMARY KEY (project_id,recovery,kind,source,namespace,reference),
    FOREIGN KEY (project_id,recovery) REFERENCES pilot_recovery_case (project_id,id)
);

CREATE VIEW pilot_recovery_observed_effect AS
    SELECT a.project_id,'ATO_EXTERNO' AS kind,a.authority AS source,a.namespace,a.official_id AS reference,a.digest,a.subject,
           CASE WHEN a.namespace='SIM-RECIBOS-EMISSAO' THEN jsonb_build_object(
               'verification',v.registry_id,'attemptId',a.subject,'target',a.response#>>'{attempt,externalTarget}','account',a.response#>>'{attempt,account}','predecessor',a.response#>>'{attempt,predecessor}',
               'lines',(SELECT jsonb_agg(line - 'position'::text ORDER BY line#>>'{vintage,start}',line#>>'{vintage,endExclusive}')
                        FROM jsonb_array_elements(a.response#>'{attempt,lines}') line)
           ) END AS binding
    FROM pilot_listing_external_act a LEFT JOIN pilot_verification_activation v
      ON (v.project_id,v.verification)=(a.project_id,(CASE WHEN a.namespace='SIM-RECIBOS-EMISSAO' THEN (a.response#>>'{attempt,verification}')::uuid END))
    UNION ALL
    SELECT project_id,'BRUTO',source_id,'ED_EC',sha256,encode(sha256(bytes),'hex'),id,NULL FROM pilot_import_file
    UNION ALL
    SELECT project_id,'RECIBO',destination,'DOSSIER',code,
           encode(sha256(convert_to((((body->'input') - 'exportId'::text - 'receivedAt'::text) ||
               jsonb_build_object('instant',extract(epoch FROM (body#>>'{input,receivedAt}')::timestamptz)))::text,'UTF8')),'hex'),
           export_id,NULL
    FROM pilot_document_receipt
    UNION ALL
    SELECT o.project_id,'FATO_ED_EC',f.source_id,o.period,
           concat_ws(':',f.sha256,j.mapping_id,j.mapping_version,r.line,o.version),o.physical_hash,o.id,NULL
    FROM pilot_observation_revision o JOIN pilot_import_row r ON (r.project_id,r.id)=(o.project_id,o.row_id)
    JOIN pilot_import_job j ON (j.project_id,j.id)=(r.project_id,r.job_id)
    JOIN pilot_import_file f ON (f.project_id,f.id)=(j.project_id,j.file_id);
