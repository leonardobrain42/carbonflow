-- CF-UX-TECH-001 UX-03B: preserve the already applied V69 checksum.
-- Add the per-link submitted PDF filename as a NEW migration.
-- For links created before this column existed, only the original filename
-- on their canonical encrypted PDF blob is available. Do not infer a new name.
ALTER TABLE app_open_asset_evidence
  ADD COLUMN submitted_file_name text;

UPDATE app_open_asset_evidence AS link
SET submitted_file_name = blob.file_name
FROM app_project_evidence_blob AS blob
WHERE blob.project_id = link.project_id
  AND blob.id = link.evidence_blob_id
  AND blob.mime_type = 'application/pdf';

ALTER TABLE app_open_asset_evidence
  ALTER COLUMN submitted_file_name SET NOT NULL;

ALTER TABLE app_open_asset_evidence
  ADD CONSTRAINT app_open_asset_evidence_submitted_file_name_check
  CHECK (submitted_file_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$');
