-- Declared search coverage by programme and credit category, not proof of completeness.
CREATE TABLE app_open_dy_search_coverage (
 assessment_id uuid NOT NULL REFERENCES app_open_dy_assessment(id),
 ordinal integer NOT NULL CHECK (ordinal BETWEEN 1 AND 100),
 program text NOT NULL CHECK (length(btrim(program)) BETWEEN 1 AND 120),
 credit_kind text NOT NULL CHECK (credit_kind IN ('ERC','ERF')),
 territory text NOT NULL CHECK (territory IN ('NATIONAL_BR','PARTIAL_BR')),
 result text NOT NULL CHECK (result IN ('FOUND','NO_RESULTS','INCOMPLETE')),
 searched_from date NOT NULL,
 searched_until_exclusive date NOT NULL,
 searched_on date NOT NULL,
 source_id text NOT NULL,
 source_edition text NOT NULL,
 locator text NOT NULL CHECK (length(btrim(locator)) BETWEEN 3 AND 1000),
 limitations text NOT NULL CHECK (length(btrim(limitations)) BETWEEN 8 AND 2000),
 PRIMARY KEY (assessment_id,ordinal),
 FOREIGN KEY (assessment_id,source_id,source_edition)
   REFERENCES app_open_dy_source_license(assessment_id,source_id,source_edition),
 CHECK (searched_until_exclusive>searched_from),
 CHECK (result='INCOMPLETE' OR searched_on>=searched_until_exclusive)
);
CREATE UNIQUE INDEX app_open_dy_search_coverage_distinct
 ON app_open_dy_search_coverage(assessment_id,lower(program),credit_kind);
CREATE TRIGGER app_open_dy_search_coverage_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_search_coverage
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
