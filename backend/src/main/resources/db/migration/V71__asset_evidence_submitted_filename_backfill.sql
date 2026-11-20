-- UX-03B compatibility fix. V69 is immutable once applied.
-- Backfill document-specific names from their existing encrypted PDF blob.
-- A later upload with the same SHA-256 can use a different submitted name
-- without changing the deduplicated blob or altering any prior documentary claim.
-- Never rewrite old V69 or use Flyway repair to conceal schema drift.
ALTER TABLE app_open_asset_evidence
  ADD COLUMN submitted_file_name text;

-- V69 installs an immutable-row trigger. Temporarily suspend only that
-- trigger under Flyway's PostgreSQL transactional DDL lock to backfill
-- already existing proof metadata. The original rows/revisions and encrypted
-- blobs remain intact; regular mutations stay forbidden after migration.
ALTER TABLE app_open_asset_evidence
  DISABLE TRIGGER app_open_asset_evidence_immutable;

UPDATE app_open_asset_evidence AS evidence
SET submitted_file_name = blob.file_name
FROM app_project_evidence_blob AS blob
WHERE evidence.project_id = blob.project_id
  AND evidence.evidence_blob_id = blob.id;

ALTER TABLE app_open_asset_evidence
  ENABLE TRIGGER app_open_asset_evidence_immutable;

-- Fail the transaction rather than silently fabricating a legacy file name.
-- Legacy PDFs were stored through storeSupportPdf, which validates this name.
DO $$
BEGIN
  IF EXISTS (
      SELECT 1 FROM app_open_asset_evidence
      WHERE submitted_file_name IS NULL
        OR submitted_file_name !~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'
        OR submitted_file_name LIKE '%..%'
  ) THEN
    RAISE EXCEPTION 'V71 backfill failed: legacy asset evidence file names require manual review';
  END IF;
END
$$;

ALTER TABLE app_open_asset_evidence
  ALTER COLUMN submitted_file_name SET NOT NULL;

ALTER TABLE app_open_asset_evidence
  ADD CONSTRAINT app_open_asset_evidence_submitted_file_name_check
  CHECK (submitted_file_name ~ '^[A-Za-z0-9][A-Za-z0-9._-]{4,115}[.]pdf$'
    AND submitted_file_name NOT LIKE '%..%');
