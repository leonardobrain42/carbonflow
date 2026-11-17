INSERT INTO pilot_recovery_case (project_id,id,manifest_sha,checkpoint,observed_through,gap_sha,incident_at)
SELECT id, :'backup'::uuid, :'manifest', :'checkpoint'::timestamptz, :'through'::timestamptz,
       :'gap', :'incident'::timestamptz
FROM pilot_project;

INSERT INTO pilot_recovery_run (project_id,recovery,run_id,snapshot_sha)
SELECT c.project_id, :'backup'::uuid, r.id, r.snapshot_sha256
FROM pilot_period_closure c JOIN pilot_selected_run r ON (r.project_id,r.id)=(c.project_id,c.run_id);

INSERT INTO pilot_recovery_effect
SELECT project_id, :'backup'::uuid, kind, source, namespace, reference, digest, binding FROM recovery_input;

INSERT INTO pilot_access_event (id,project_id,action,outcome,target,occurred_at,details)
SELECT gen_random_uuid(), id, 'RESTAURAR_COPIA_ISOLADA', 'PERMITIDO', :'backup', clock_timestamp(),
    jsonb_build_object('manifestSha',:'manifest','gapSha',:'gap','checkpoint',:'checkpoint','observedThrough',:'through')
FROM pilot_project;

UPDATE pilot_job_attempt SET state='EXPIRADA', finished_at=clock_timestamp(),
    reason='Geração de outro ambiente encerrada durante a recuperação.'
WHERE state='EM_EXECUCAO';
