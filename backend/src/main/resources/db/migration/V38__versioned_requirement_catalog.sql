CREATE TABLE app_norm_pack (
    id text PRIMARY KEY,
    methodology text NOT NULL,
    methodology_edition text NOT NULL,
    edition integer NOT NULL CHECK (edition > 0),
    state text NOT NULL CHECK (state IN ('DRAFT','PUBLISHED_SYNTHETIC')),
    UNIQUE(methodology,methodology_edition,edition)
);

CREATE TABLE app_norm_pack_window (
    pack_id text NOT NULL REFERENCES app_norm_pack(id),
    act text NOT NULL CHECK (act IN ('PD','MR')),
    date_basis text NOT NULL CHECK (date_basis IN ('ACT_DATE','PERIOD_START')),
    effective_from date NOT NULL,
    effective_until date,
    CHECK (effective_until IS NULL OR effective_until > effective_from),
    PRIMARY KEY(pack_id,act)
);

CREATE TABLE app_norm_rule (
    pack_id text NOT NULL REFERENCES app_norm_pack(id),
    code text NOT NULL,
    act text NOT NULL CHECK (act IN ('PD','MR')),
    section text NOT NULL,
    title text NOT NULL,
    source_id text NOT NULL,
    source_edition text NOT NULL,
    locator text NOT NULL,
    field text NOT NULL,
    position integer NOT NULL CHECK (position >= 0),
    condition_kind text NOT NULL CHECK (condition_kind IN ('ALWAYS','BOOLEAN_EQUALS','QUANTITY_AT_LEAST','MANUAL_REVIEW')),
    condition_field text,
    expected_boolean boolean,
    minimum_quantity numeric(20,6),
    unit text,
    CHECK (
        (condition_kind IN ('ALWAYS','MANUAL_REVIEW') AND condition_field IS NULL
            AND expected_boolean IS NULL AND minimum_quantity IS NULL AND unit IS NULL)
        OR (condition_kind = 'BOOLEAN_EQUALS' AND condition_field = 'hasEnergyRecords' AND condition_field IS NOT NULL
            AND expected_boolean IS NOT NULL AND minimum_quantity IS NULL AND unit IS NULL)
        OR (condition_kind = 'QUANTITY_AT_LEAST' AND condition_field = 'meteredEnergy' AND condition_field IS NOT NULL
            AND expected_boolean IS NULL AND minimum_quantity IS NOT NULL AND unit IS NOT NULL AND unit = 'kWh')
    ),
    PRIMARY KEY(pack_id,code),
    FOREIGN KEY(pack_id,act) REFERENCES app_norm_pack_window(pack_id,act),
    FOREIGN KEY(source_id,source_edition) REFERENCES app_source_version(source_id,edition)
);

CREATE TABLE app_norm_rule_dependency (
    pack_id text NOT NULL,
    rule_code text NOT NULL,
    depends_on text NOT NULL,
    CHECK(rule_code <> depends_on),
    PRIMARY KEY(pack_id,rule_code,depends_on),
    FOREIGN KEY(pack_id,rule_code) REFERENCES app_norm_rule(pack_id,code),
    FOREIGN KEY(pack_id,depends_on) REFERENCES app_norm_rule(pack_id,code)
);

CREATE TABLE app_regime_snapshot (
    id uuid PRIMARY KEY,
    project_id text NOT NULL,
    organization_id uuid NOT NULL,
    environment text NOT NULL,
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    pack_id text NOT NULL REFERENCES app_norm_pack(id),
    payload jsonb NOT NULL CHECK (jsonb_typeof(payload) = 'object'),
    sha256 text NOT NULL CHECK (sha256 ~ '^[a-f0-9]{64}$'),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    FOREIGN KEY(project_id,organization_id,environment) REFERENCES app_project(id,organization_id,environment),
    UNIQUE(project_id,actor_id,sha256)
);

-- Own test data. Windows and predicates deliberately illustrate the resolver, without transcribing programme rules.
INSERT INTO app_norm_pack VALUES
    ('CF-DEMO-1','VM0038','1.1',1,'PUBLISHED_SYNTHETIC'),
    ('CF-DEMO-2','VM0038','1.1',2,'PUBLISHED_SYNTHETIC');
INSERT INTO app_norm_pack_window VALUES
    ('CF-DEMO-1','PD','ACT_DATE','2020-01-01','2030-01-01'),
    ('CF-DEMO-2','PD','ACT_DATE','2030-01-01',null),
    ('CF-DEMO-1','MR','PERIOD_START','2020-01-01','2030-01-01'),
    ('CF-DEMO-2','MR','PERIOD_START','2030-01-01',null);

INSERT INTO app_norm_rule
    (pack_id,code,act,section,title,source_id,source_edition,locator,field,position,
     condition_kind,condition_field,expected_boolean,minimum_quantity,unit)
SELECT id,rule.code,rule.act,rule.section,rule.title,'CF-ECA-DEMO','1',rule.locator,rule.field,rule.position,
       rule.kind,rule.condition_field,rule.expected_boolean,rule.minimum_quantity,rule.unit
FROM app_norm_pack
CROSS JOIN (VALUES
    ('DEMO-PD-01','PD','applicability','Registrar data do ato de demonstração','DEMO-01','referenceActDate',10,
        'ALWAYS',null,null,null,null),
    ('DEMO-PD-02','PD','baseline','Revisar a hipótese de linha de base do exemplo','DEMO-03','scenario',20,
        'MANUAL_REVIEW',null,null,null,null),
    ('DEMO-MR-01','MR','quantification','Registrar o início do período de exemplo','DEMO-02','periodStart',10,
        'ALWAYS',null,null,null,null),
    ('DEMO-MR-02','MR','monitoring','Indicar se há registros energéticos no exemplo','DEMO-03','ed',20,
        'BOOLEAN_EQUALS','hasEnergyRecords',true,null,null),
    ('DEMO-MR-03','MR','monitoring','Conferir a condição de energia medida do exemplo','DEMO-03','ec',30,
        'QUANTITY_AT_LEAST','meteredEnergy',null,1.000000,'kWh')
) AS rule(code,act,section,title,locator,field,position,kind,condition_field,expected_boolean,minimum_quantity,unit);

INSERT INTO app_norm_rule_dependency
SELECT id,'DEMO-PD-02','DEMO-PD-01' FROM app_norm_pack
UNION ALL
SELECT id,'DEMO-MR-02','DEMO-MR-01' FROM app_norm_pack
UNION ALL
SELECT id,'DEMO-MR-03','DEMO-MR-01' FROM app_norm_pack;
