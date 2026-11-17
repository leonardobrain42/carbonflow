SELECT jsonb_build_object('kind','EFEITO','effect',kind,'project',project_id,
    'source',source,'namespace',namespace,'reference',reference,'sha256',digest,'binding',binding)
FROM pilot_recovery_observed_effect
ORDER BY project_id COLLATE "C", kind COLLATE "C", source COLLATE "C", namespace COLLATE "C", reference COLLATE "C";
