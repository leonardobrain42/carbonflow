SELECT pg_advisory_xact_lock(hashtextextended('instalar-recuperacao-carbonflow',0));

CREATE TEMP TABLE recovery_input (
    project_id text NOT NULL,
    kind text NOT NULL,
    source text NOT NULL,
    namespace text NOT NULL,
    reference text NOT NULL,
    digest text NOT NULL,
    binding jsonb,
    PRIMARY KEY (project_id,kind,source,namespace,reference)
) ON COMMIT DROP;
