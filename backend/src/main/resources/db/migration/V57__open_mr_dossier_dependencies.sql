-- CF-OPEN-009 / #98: explicit MR preparation dependencies in dynamic app_project.
-- A draft MR can reference a QA-only period. It NEVER admits ED/EC to E8 or
-- creates a VVB/Verra-approved monitoring report.
CREATE TABLE app_open_mr_dossier_revision (
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 dossier_code text NOT NULL CHECK(dossier_code ~ '^[A-Za-z0-9][A-Za-z0-9._-]{0,31}$'),
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 snapshot_id uuid NOT NULL,
 source_sha256 bytea NOT NULL CHECK(octet_length(source_sha256)=32),
 note text NOT NULL CHECK(char_length(btrim(note)) BETWEEN 15 AND 2000),
 authored_by uuid NOT NULL REFERENCES app_identity(id),
 created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,dossier_code,version),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,snapshot_id)
   REFERENCES app_open_period_qa_snapshot(project_id,id)
);
CREATE INDEX app_open_mr_dossier_revisions_project
 ON app_open_mr_dossier_revision(project_id,dossier_code,version DESC);

-- Bind the PD monitoring/quantification narrative to the exact MR dossier
-- revision manifest that was present when it was authored. This is not a
-- normative or method-specific E8 dependence, only document provenance.
CREATE TABLE app_pd_monitoring_dependency (
 project_id text NOT NULL,
 section_id text NOT NULL CHECK(section_id IN ('monitoring','quantification')),
 section_version integer NOT NULL,
 manifest_sha256 char(64) NOT NULL CHECK(manifest_sha256 ~ '^[0-9a-f]{64}$'),
 recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,section_id,section_version),
 FOREIGN KEY(project_id,section_id,section_version)
   REFERENCES app_pd_section_version(project_id,section_id,version)
);
CREATE TRIGGER app_open_mr_dossier_revision_immutable
 BEFORE UPDATE OR DELETE ON app_open_mr_dossier_revision
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_pd_monitoring_dependency_immutable
 BEFORE UPDATE OR DELETE ON app_pd_monitoring_dependency
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
