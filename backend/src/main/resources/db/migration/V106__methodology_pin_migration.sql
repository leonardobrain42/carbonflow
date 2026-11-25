-- D18/CF-PACKAGES-001: explicit migration of a project's pinned methodology version. A migration is a new row of the
-- append-only pin table that points at the pin it replaces; nothing is edited or deleted, and the earlier pin stays
-- readable. 'TRIAGE' rows are written when the candidate changes; 'MIGRATION' rows only by an explicit request.
ALTER TABLE app_project_methodology_pin
 ADD COLUMN origin text NOT NULL DEFAULT 'TRIAGE' CHECK (origin IN ('TRIAGE','MIGRATION')),
 ADD COLUMN migrated_from integer CHECK (migrated_from IS NULL OR migrated_from > 0),
 ADD CONSTRAINT app_project_methodology_pin_migration_shape
   CHECK ((origin = 'MIGRATION') = (migrated_from IS NOT NULL)
          AND (migrated_from IS NULL OR migrated_from < pin_version)
          AND (origin <> 'MIGRATION' OR methodology IS NOT NULL));
