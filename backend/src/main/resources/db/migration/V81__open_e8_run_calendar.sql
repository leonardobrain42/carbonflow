ALTER TABLE app_open_e8_run
  ADD COLUMN temporal_context jsonb
    CHECK (jsonb_typeof(temporal_context) = 'object');

ALTER TABLE app_open_e8_run_command
  ADD COLUMN request_sha256 bytea
    CHECK (octet_length(request_sha256) = 32);
