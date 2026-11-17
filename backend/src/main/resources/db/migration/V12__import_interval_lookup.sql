CREATE INDEX pilot_import_row_interval
    ON pilot_import_row USING gist (tstzrange(start_at,end_at,'[)'))
    WHERE physical_hash IS NOT NULL;
