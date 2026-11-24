-- Declared geographic attribution, not normative admission.
CREATE TABLE app_open_dy_allocation (
 assessment_id uuid NOT NULL,
 issuance_ordinal integer NOT NULL,
 method text NOT NULL CHECK (method IN ('DIRECT_BR','DOCUMENTED_SHARE')),
 total_tco2e numeric(30,12) NOT NULL CHECK (total_tco2e>0),
 share_numerator numeric(30,12) NOT NULL CHECK (share_numerator>0),
 share_denominator numeric(30,12) NOT NULL CHECK (share_denominator>=share_numerator),
 brazil_tco2e numeric(30,12) NOT NULL CHECK (brazil_tco2e>0 AND brazil_tco2e<=total_tco2e),
 basis_unit text NOT NULL CHECK (length(btrim(basis_unit)) BETWEEN 1 AND 80),
 source_id text NOT NULL,
 source_edition text NOT NULL,
 locator text NOT NULL CHECK (length(btrim(locator)) BETWEEN 3 AND 1000),
 rationale text NOT NULL CHECK (length(btrim(rationale)) BETWEEN 15 AND 2000),
 PRIMARY KEY (assessment_id,issuance_ordinal),
 FOREIGN KEY (assessment_id,issuance_ordinal) REFERENCES app_open_dy_issuance(assessment_id,ordinal),
 FOREIGN KEY (assessment_id,source_id,source_edition)
   REFERENCES app_open_dy_source_license(assessment_id,source_id,source_edition),
 CHECK (brazil_tco2e*share_denominator=total_tco2e*share_numerator),
 CHECK ((method='DIRECT_BR' AND share_numerator=1 AND share_denominator=1)
     OR (method='DOCUMENTED_SHARE' AND share_numerator<share_denominator))
);
CREATE TRIGGER app_open_dy_allocation_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_allocation
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
