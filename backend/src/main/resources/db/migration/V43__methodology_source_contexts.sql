-- CF-OPEN-001: context registry for cross-methodology coverage.
-- These records describe intended journeys; ACTIVE is never synonymous with an admitted methodology.
CREATE TABLE app_methodology_source_context (
    methodology text NOT NULL,
    methodology_edition text NOT NULL,
    scenario text NOT NULL,
    title text NOT NULL CHECK (btrim(title) <> ''),
    country text NOT NULL CHECK (country ~ '^[A-Z]{2}$'),
    network_mode text NOT NULL,
    vehicle_category text NOT NULL,
    charger_type text NOT NULL,
    energy_system text NOT NULL,
    catalog_state text NOT NULL DEFAULT 'ACTIVE' CHECK (catalog_state IN ('ACTIVE','SUSPENDED')),
    effective_from date,
    effective_until date,
    CHECK (effective_until IS NULL OR effective_from IS NULL OR effective_until > effective_from),
    PRIMARY KEY(methodology, methodology_edition, scenario)
);

INSERT INTO app_methodology_source_context
(methodology,methodology_edition,scenario,title,country,network_mode,vehicle_category,charger_type,energy_system)
VALUES ('VM0038','1.1','OPEN_LDV_DCFC_SIN_BR',
        'Brasil · recarga aberta · BEV leves · DCFC · SIN','BR','OPEN','LDV_BEV','DCFC','SIN');

ALTER TABLE app_methodology_source_need
 ADD CONSTRAINT app_source_need_registered_context
 FOREIGN KEY(methodology,methodology_edition,scenario)
 REFERENCES app_methodology_source_context(methodology,methodology_edition,scenario);

ALTER TABLE app_methodology_source_need
 ADD COLUMN blocked_capability text NOT NULL DEFAULT 'PREPARATION'
     CHECK (blocked_capability IN ('PREPARATION','APPLICABILITY','BASELINE_BE',
         'PROJECT_EMISSIONS_PE','EMISSION_REDUCTIONS_ER','ADDITIONALITY',
         'MONITORING','PD','AI_SUPPORT')),
 ADD COLUMN edition_policy text NOT NULL DEFAULT 'EXACT'
     CHECK (edition_policy IN ('EXACT','YEAR','LATEST')),
 ADD COLUMN responsible_party text NOT NULL DEFAULT 'CARBONFLOW'
     CHECK (responsible_party IN ('CARBONFLOW','PROPONENT','OPERATOR')),
 ADD COLUMN priority text NOT NULL DEFAULT 'P0'
     CHECK (priority IN ('P0','P1','P2'));

UPDATE app_methodology_source_need SET edition_policy = 'YEAR' WHERE source_edition = 'YEAR';
UPDATE app_methodology_source_need SET edition_policy = 'LATEST' WHERE source_edition = 'LATEST';
UPDATE app_methodology_source_need SET responsible_party = 'PROPONENT'
 WHERE kind = 'PROJECT_EVIDENCE';
UPDATE app_methodology_source_need SET responsible_party = 'OPERATOR'
 WHERE code = 'OPEN-ED-EC';
UPDATE app_methodology_source_need SET blocked_capability = CASE
    WHEN code IN ('OPEN-METHOD','OPEN-EV-PENETRATION','OPEN-EV-SALES','OPEN-RIGHTS') THEN 'APPLICABILITY'
    WHEN code IN ('OPEN-EVR','OPEN-PBEV','OPEN-FOSSIL-EF') THEN 'BASELINE_BE'
    WHEN code IN ('OPEN-VT0011','OPEN-TOOL07','OPEN-GRID-EF') THEN 'PROJECT_EMISSIONS_PE'
    WHEN code IN ('OPEN-POSITIVE','OPEN-VCS','OPEN-VT0008','OPEN-REG-SURPLUS','OPEN-CHRONOLOGY') THEN 'ADDITIONALITY'
    WHEN code = 'OPEN-ED-EC' THEN 'MONITORING'
    WHEN code = 'OPEN-PD' THEN 'PD'
    ELSE 'PREPARATION' END;
UPDATE app_methodology_source_need SET priority = 'P1' WHERE NOT mandatory;

CREATE INDEX app_method_source_need_status_filters
 ON app_methodology_source_need(methodology, methodology_edition, scenario, stage, priority);
