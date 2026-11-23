-- Freeze the calendar and source year alongside each new E8 rehearsal.
-- Legacy attempts have no timing row because their calendar cannot be inferred.
CREATE TABLE app_open_e8_run_timing (
 project_id text NOT NULL,
 id uuid NOT NULL,
 timing_sha256 bytea NOT NULL CHECK (octet_length(timing_sha256) = 32),
 window_start timestamptz,
 window_end_exclusive timestamptz,
 project_year integer CHECK (project_year BETWEEN 1 AND 100),
 source_year integer,
 PRIMARY KEY (project_id, id),
 FOREIGN KEY (project_id, id) REFERENCES app_open_e8_run(project_id, id),
 CHECK ((window_start IS NULL AND window_end_exclusive IS NULL) OR
        (window_start IS NOT NULL AND window_end_exclusive > window_start
         AND window_end_exclusive IS NOT NULL)),
 CHECK ((project_year IS NULL AND source_year IS NULL) OR
        (project_year IS NOT NULL AND source_year IS NOT NULL AND window_start IS NOT NULL))
);
CREATE TRIGGER app_open_e8_run_timing_immutable
 BEFORE UPDATE OR DELETE ON app_open_e8_run_timing
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
