-- D18/CF-PACKAGES-001 slice 3: the project pins the registry version and the code package of its candidate
-- methodology. Append-only (a change of candidate, registry version or package digest adds a row). A newer registry
-- version or package never alters a pinned project; migrating is an explicit later operation.
-- The pin records which versions the project was prepared against. It does not admit data, grant a licence,
-- enable calculation or confirm eligibility.
CREATE TABLE app_project_methodology_pin (
 project_id text NOT NULL,
 pin_version integer NOT NULL CHECK (pin_version > 0),
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK (environment = 'SANDBOX'),
 triage_version integer NOT NULL CHECK (triage_version > 0),
 methodology text CHECK (methodology IS NULL OR methodology ~ '^[A-Z][A-Z0-9_-]{2,39}$'),
 methodology_edition text CHECK (methodology_edition IS NULL OR methodology_edition ~ '^[0-9A-Za-z][0-9A-Za-z._-]{0,19}$'),
 registry_version integer CHECK (registry_version IS NULL OR registry_version > 0),
 registry_digest text CHECK (registry_digest IS NULL OR registry_digest ~ '^[a-f0-9]{64}$'),
 package_id text,
 package_version integer CHECK (package_version IS NULL OR package_version > 0),
 package_digest text CHECK (package_digest IS NULL OR package_digest ~ '^[a-f0-9]{64}$'),
 catalog_version integer CHECK (catalog_version IS NULL OR catalog_version > 0),
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 pinned_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK ((methodology IS NULL) = (methodology_edition IS NULL)),
 CHECK (methodology IS NOT NULL OR (registry_version IS NULL AND package_id IS NULL)),
 CHECK ((registry_version IS NULL) = (registry_digest IS NULL)),
 CHECK ((package_id IS NULL) = (package_version IS NULL AND package_digest IS NULL AND catalog_version IS NULL)
        OR (package_id IS NOT NULL AND package_version IS NOT NULL AND package_digest IS NOT NULL
            AND catalog_version IS NOT NULL)),
 PRIMARY KEY (project_id, pin_version),
 FOREIGN KEY (project_id, organization_id, environment) REFERENCES app_project(id, organization_id, environment)
);
CREATE TRIGGER app_project_methodology_pin_immutable
 BEFORE UPDATE OR DELETE ON app_project_methodology_pin
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
