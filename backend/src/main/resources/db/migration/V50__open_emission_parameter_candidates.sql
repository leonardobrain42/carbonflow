-- CF-OPEN-007 / #96, first slice: typed, reviewable emission-factor candidates.
-- The source object, rights and technical approval remain independent from parameter
-- conversion. No value in these tables is admitted to E8 or a project by default.
-- This migration follows V49 from CF-OPEN-006 / PR #111.

CREATE TABLE app_open_parameter_candidate (
    id uuid PRIMARY KEY,
    organization_id uuid NOT NULL REFERENCES app_organization(id),
    source_object_id uuid NOT NULL,
    source_license_id uuid NOT NULL REFERENCES app_content_license(id),
    source_row integer NOT NULL CHECK (source_row BETWEEN 2 AND 2001),
    source_sha256 bytea NOT NULL CHECK (octet_length(source_sha256) = 32),
    source_locator text NOT NULL CHECK (length(btrim(source_locator)) BETWEEN 3 AND 300),
    created_by uuid NOT NULL REFERENCES app_identity(id),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    methodology text NOT NULL CHECK (length(btrim(methodology)) BETWEEN 3 AND 40),
    methodology_edition text NOT NULL CHECK (length(btrim(methodology_edition)) BETWEEN 1 AND 40),
    scenario text NOT NULL CHECK (length(btrim(scenario)) BETWEEN 3 AND 80),
    geography text NOT NULL CHECK (geography = 'BR'),
    period_year integer NOT NULL CHECK (period_year BETWEEN 1900 AND 2100),
    parameter_kind text NOT NULL CHECK (parameter_kind IN ('EF_GRID','EF_FUEL','IR','DY')),
    gas text CHECK (gas IN ('CO2','CO2e','CH4','N2O')),
    fuel text CHECK (fuel IN ('GASOLINE','DIESEL','ETHANOL','FLEX')),
    grid text CHECK (grid IN ('SIN')),
    original_unit text NOT NULL CHECK (length(btrim(original_unit)) BETWEEN 1 AND 60),
    original_value numeric(30,12) CHECK (original_value >= 0),
    normalized_unit text,
    normalized_value numeric(30,18) CHECK (normalized_value >= 0),
    conversion_formula text,
    technical_rule_ref text,
    technical_evidence text,
    status text NOT NULL DEFAULT 'CANDIDATE'
        CHECK (status IN ('CANDIDATE','SUSPENDED')),
    purged_at timestamptz,
    UNIQUE(id,organization_id),
    UNIQUE NULLS NOT DISTINCT
      (organization_id,source_object_id,source_row,parameter_kind,gas,fuel,period_year),
    FOREIGN KEY(source_object_id,organization_id)
        REFERENCES app_platform_source_object(id,organization_id),
    FOREIGN KEY(source_object_id,source_license_id)
        REFERENCES app_platform_source_object(id,license_id),
    CHECK (
      (parameter_kind = 'EF_GRID' AND grid = 'SIN' AND fuel IS NULL AND gas IS NOT NULL) OR
      (parameter_kind = 'EF_FUEL' AND grid IS NULL AND fuel IS NOT NULL AND gas IS NOT NULL) OR
      (parameter_kind IN ('IR','DY') AND grid IS NULL AND fuel IS NULL AND gas IS NULL)
    ),
    CHECK (
      (parameter_kind IN ('IR','DY') AND normalized_value IS NULL
        AND normalized_unit IS NULL AND conversion_formula IS NULL)
      OR parameter_kind IN ('EF_GRID','EF_FUEL')
    ),
    CHECK ((purged_at IS NULL) OR
      (status = 'SUSPENDED' AND original_value IS NULL AND normalized_value IS NULL))
);

CREATE INDEX app_open_parameter_candidate_scope
 ON app_open_parameter_candidate(organization_id,methodology,methodology_edition,
  scenario,parameter_kind,period_year);
CREATE INDEX app_open_parameter_candidate_source
 ON app_open_parameter_candidate(source_object_id,source_license_id);

-- A review is scoped to edition, methodology, purpose, vintage and locator.
-- APPROVED_FOR_SCOPE only means an internal evaluation, not VCS/VT0011 admission.
CREATE TABLE app_open_parameter_assessment (
    id uuid PRIMARY KEY,
    candidate_id uuid NOT NULL REFERENCES app_open_parameter_candidate(id),
    methodology text NOT NULL,
    methodology_edition text NOT NULL,
    scenario text NOT NULL,
    blocked_capability text NOT NULL CHECK (length(btrim(blocked_capability)) BETWEEN 3 AND 80),
    outcome text NOT NULL CHECK (outcome IN
       ('APPROVED_FOR_SCOPE','REJECTED_FOR_SCOPE','NEEDS_INFORMATION')),
    rule_reference text NOT NULL CHECK (length(btrim(rule_reference)) BETWEEN 5 AND 300),
    evidence_locator text NOT NULL CHECK (length(btrim(evidence_locator)) BETWEEN 3 AND 300),
    reason text NOT NULL CHECK (length(btrim(reason)) BETWEEN 15 AND 2500),
    reviewed_by uuid NOT NULL REFERENCES app_identity(id),
    reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    UNIQUE(candidate_id,methodology,methodology_edition,scenario,blocked_capability)
);

-- Structural safeguard against self-review; staff rights, license verification,
-- validity, and real publication must still be checked by the service (future slice).
CREATE FUNCTION app_open_parameter_assessment_guard() RETURNS trigger AS $$
DECLARE author_id uuid; factor_status text;
BEGIN
 SELECT created_by,status INTO author_id,factor_status
 FROM app_open_parameter_candidate WHERE id = NEW.candidate_id FOR UPDATE;
 IF factor_status IS DISTINCT FROM 'CANDIDATE' THEN
   RAISE EXCEPTION 'Parameter candidate is not available for assessment';
 END IF;
 IF author_id = NEW.reviewed_by THEN
   RAISE EXCEPTION 'Independent reviewer required';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_parameter_assessment_independent
 BEFORE INSERT ON app_open_parameter_assessment
 FOR EACH ROW EXECUTE FUNCTION app_open_parameter_assessment_guard();

-- Purge only numerical derivatives; retain hashes, locators and review history.
-- Original encrypted objects are purged by the preexisting #92 trigger.
CREATE FUNCTION app_open_parameter_license_revocation() RETURNS trigger AS $$
BEGIN
 IF NEW.outcome IN ('REVOKED','DENIED') THEN
   UPDATE app_open_parameter_candidate
   SET original_value = NULL, normalized_value = NULL,
       status = 'SUSPENDED', purged_at = statement_timestamp()
   WHERE source_license_id = NEW.license_id AND status <> 'SUSPENDED';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_parameter_source_revoked
 AFTER INSERT ON app_content_license_decision
 FOR EACH ROW EXECUTE FUNCTION app_open_parameter_license_revocation();

-- Deliberately absent: ProjectParameterBinding, run/snapshot E8 authorization,
-- IR/Dy default values and any table of published official factors.
