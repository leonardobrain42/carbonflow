-- Keep the memory shape gate; accept both immutable engine generations.
ALTER TABLE app_open_e8_run DROP CONSTRAINT app_open_e8_run_check2;
ALTER TABLE app_open_e8_run ADD CONSTRAINT app_open_e8_run_check2 CHECK(
   (state='BLOCKED' AND engine IS NULL AND afec IS NULL AND mpg IS NULL
     AND be_tco2e IS NULL AND pe_tco2e IS NULL AND le_tco2e IS NULL
     AND dy IS NULL AND er_tco2e IS NULL AND er_display IS NULL
     AND trace_json IS NULL)
   OR
   (state='REVIEWED_INTERNAL' AND engine IN ('vm0038-open-grid-arithmetic@1','vm0038-open-grid-arithmetic@2-hourly')
     AND afec IS NOT NULL AND char_length(afec) BETWEEN 1 AND 80
     AND mpg IS NOT NULL AND char_length(mpg) BETWEEN 1 AND 80
     AND be_tco2e IS NOT NULL AND char_length(be_tco2e) BETWEEN 1 AND 80
     AND pe_tco2e IS NOT NULL AND char_length(pe_tco2e) BETWEEN 1 AND 80
     AND le_tco2e IS NOT NULL AND char_length(le_tco2e) BETWEEN 1 AND 80
     AND dy IS NOT NULL AND char_length(dy) BETWEEN 1 AND 80
     AND er_tco2e IS NOT NULL AND char_length(er_tco2e) BETWEEN 1 AND 80
     AND er_display IS NOT NULL AND char_length(er_display) BETWEEN 1 AND 80
     AND trace_json IS NOT NULL));
