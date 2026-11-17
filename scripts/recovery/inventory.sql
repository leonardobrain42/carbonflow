SET LOCAL TIME ZONE 'UTC';
SET LOCAL DateStyle TO 'ISO, YMD';
SET LOCAL bytea_output TO 'hex';

WITH objects(project_id, kind, reference, bytes, expected) AS (
    SELECT project_id, 'ARTEFATO', id||'@'||version, content, sha256 FROM pilot_artifact
    UNION ALL SELECT project_id, 'BRUTO', id||'@1', bytes, sha256 FROM pilot_import_file
    UNION ALL SELECT project_id, 'MANIFESTO', artifact_id||'@'||revision, manifest, NULL FROM pilot_document_variant
    UNION ALL SELECT project_id, 'FECHAMENTO', period||'@'||revision, bytes, sha256 FROM pilot_period_closure
    UNION ALL SELECT project_id, 'MEMORIA', id::text, snapshot, snapshot_sha256 FROM pilot_selected_run WHERE snapshot IS NOT NULL
    UNION ALL SELECT project_id, 'REFERENCIA', id::text, snapshot, report->>'snapshotSha256' FROM synthetic_run
)
SELECT jsonb_build_object('kind', 'OBJETO', 'category', kind, 'project', project_id, 'reference', reference,
    'bytes', octet_length(bytes), 'sha256', encode(sha256(bytes),'hex'),
    'valid', CASE WHEN kind='MANIFESTO' THEN true ELSE expected=encode(sha256(bytes),'hex') END)
FROM objects ORDER BY project_id COLLATE "C", kind COLLATE "C", reference COLLATE "C";

SELECT jsonb_build_object('kind','DOSSIE_INCOMPLETO','project',d.project_id,'reference',d.id||'@'||d.revision)
FROM pilot_document_dossier d LEFT JOIN pilot_document_variant v
ON (v.project_id,v.dossier_id,v.revision)=(d.project_id,d.id,d.revision)
WHERE d.state='CONGELADO'
GROUP BY d.project_id,d.id,d.revision,d.command
HAVING count(v.artifact_id) != CASE WHEN d.command->>'predecessor' IS NULL THEN 2 ELSE 4 END
ORDER BY d.project_id,d.id,d.revision;

SELECT jsonb_build_object('kind','EFEITO','effect',kind,'project',project_id,
    'source',source,'namespace',namespace,'reference',reference,'sha256',digest,'binding',binding)
FROM pilot_recovery_observed_effect
ORDER BY project_id COLLATE "C", kind COLLATE "C", source COLLATE "C", namespace COLLATE "C", reference COLLATE "C";

-- Conferência de todas as tabelas, inclusive versões, grants, chaves de comando, trabalhos e trilha.
-- Sessões e desafios transitórios são omitidos. A conta preservada autentica de novo depois do restauro.
SELECT format($command$
    SELECT jsonb_build_object('kind','TABELA','name',%L,'count',count(*),
        'sha256',encode(sha256(convert_to(coalesce(string_agg(row_hash,'' ORDER BY row_hash COLLATE "C"),''),'UTF8')),'hex'))
    FROM (SELECT encode(sha256(convert_to(to_jsonb(t)::text,'UTF8')),'hex') AS row_hash FROM public.%I t) hashes
$command$, tablename, tablename)
FROM pg_tables WHERE schemaname='public'
    AND tablename NOT IN ('pilot_session', 'app_account_session', 'app_account_challenge')
ORDER BY tablename COLLATE "C"
\gexec
