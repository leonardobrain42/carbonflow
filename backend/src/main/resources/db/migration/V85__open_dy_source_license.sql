-- #96: immutable license decision pins for the exact assessment revision.
CREATE TABLE app_open_dy_source_license (
 assessment_id uuid NOT NULL REFERENCES app_open_dy_assessment(id),
 source_id text NOT NULL,
 source_edition text NOT NULL,
 license_id uuid NOT NULL,
 decision_version integer NOT NULL CHECK (decision_version>0),
 PRIMARY KEY (assessment_id,source_id,source_edition),
 FOREIGN KEY (source_id,source_edition) REFERENCES app_source_version(source_id,edition),
 FOREIGN KEY (license_id,decision_version) REFERENCES app_content_license_decision(license_id,version)
);
CREATE TRIGGER app_open_dy_source_license_immutable
 BEFORE UPDATE OR DELETE ON app_open_dy_source_license
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
