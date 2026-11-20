-- CF-UX-TECH-001 UX-03B: preserve the already applied V69 checksum.
-- Add per-link submitted PDF filenames in this single additive V69.1 migration.
-- For pre-existing links, only the encrypted blob's original filename is known.
-- Do not fabricate a filename or alter the submitted claim, SHA-256 or revision.
ALTER TABLE app_open_asset_evidence
  ADD COLUMN submitted_file_name text;

-- V69 made these evidence links immutable. Under Flyway's transaction and
-- exclusive ALTER TABLE lock, suspend just that UPDATE/DELETE guard while
-- backfilling the newly added metadata field. Re-enable it before commit.
-- PostgreSQL transaction rollback also restores the original trigger state.
ALTER TABLE app_open_asset_evidence
  DISABLE TRIGGER app_open_asset_evidence_immutable;

UPDATE app_open_asset_evidence AS link
SET submitted_file_name = blob.file_name
FROM app_project_evidence_blob AS blob
WHERE blob.project_id = link.project_id
  AND blob.id = link.evidence_blob_id
  AND blob.mime_type = 'application/pdf';

ALTER TABLE app_open_asset_evidence
  ENABLE TRIGGER app_open_asset_evidence_immutable;

-- Fail closed rather than silently invent names for legacy rows.
-- storeSupportPdf validated PDF names before encryption and linkage.
DO $$
BEGIN
 IF EXISTS (
   SELECT 1 FROM app_open_asset_evidence
   WHERE submitted_file_name IS NULL
     OR submitted_file_name !~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'
     OR submitted_file_name LIKE '%..%'
 ) THEN
   RAISE EXCEPTION 'V69.1 backfill: an existing document needs manual filename review';
 END IF;
END
$$;

ALTER TABLE app_open_asset_evidence
  ALTER COLUMN submitted_file_name SET NOT NULL;

ALTER TABLE app_open_asset_evidence
  ADD CONSTRAINT app_open_asset_evidence_submitted_file_name_check
  CHECK (submitted_file_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'
      AND submitted_file_name NOT LIKE '%..%');
