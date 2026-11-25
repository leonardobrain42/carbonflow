-- D14 Q05.1: itemized issuances from the start of crediting up to (excluding) the annual window.
-- Additive and immutable. Attached to one assessment revision that declares cumulative totals.
-- Reading stays the annual window; nothing here is admitted or consumed by the executor.
CREATE TABLE app_open_dy_cumulative_item (
 assessment_id uuid NOT NULL REFERENCES app_open_dy_assessment(id),
 ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
 program text NOT NULL CHECK (length(btrim(program)) BETWEEN 1 AND 120),
 registry_project text NOT NULL CHECK (length(btrim(registry_project)) BETWEEN 1 AND 160),
 issuance_id text NOT NULL CHECK (length(btrim(issuance_id)) BETWEEN 1 AND 160),
 credit_kind text NOT NULL CHECK (credit_kind IN ('ERC','ERF')),
 issued_on date NOT NULL,
 geography text NOT NULL CHECK (geography='BR'),
 credits_tco2e numeric(30,12) NOT NULL CHECK (credits_tco2e>0),
 source_id text NOT NULL,
 source_edition text NOT NULL,
 locator text NOT NULL CHECK (length(btrim(locator)) BETWEEN 3 AND 1000),
 PRIMARY KEY (assessment_id,ordinal),
 FOREIGN KEY (source_id,source_edition) REFERENCES app_source_version(source_id,edition)
);
CREATE UNIQUE INDEX app_open_dy_cumulative_item_distinct
 ON app_open_dy_cumulative_item (assessment_id,lower(program),lower(registry_project),lower(issuance_id));
CREATE TRIGGER app_open_dy_cumulative_item_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_cumulative_item
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
