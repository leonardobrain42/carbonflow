-- CF-OPEN-007/#96: claimant-supplied hourly SIN electricity factor series.
-- A source declaration is NOT VT0011 compliance or normative admission.
CREATE TABLE app_open_efel_hourly_candidate (
 id uuid PRIMARY KEY,
 project_id text NOT NULL,
 organization_id uuid NOT NULL,
 environment text NOT NULL CHECK(environment='SANDBOX'),
 period_code text NOT NULL,
 energy_candidate_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 methodology text NOT NULL DEFAULT 'VM0038' CHECK(methodology='VM0038'),
 methodology_edition text NOT NULL DEFAULT '1.1' CHECK(methodology_edition='1.1'),
 region text NOT NULL DEFAULT 'SIN' CHECK(region='SIN'),
 gas text NOT NULL DEFAULT 'CO2e' CHECK(gas='CO2e'),
 temporal_resolution text NOT NULL DEFAULT 'HOURLY' CHECK(temporal_resolution='HOURLY'),
 original_unit text NOT NULL CHECK(original_unit IN
    ('tCO2e/MWh','tCO2e/kWh','kgCO2e/kWh')),
 normalized_unit text NOT NULL DEFAULT 'tCO2e/kWh' CHECK(normalized_unit='tCO2e/kWh'),
 source_title text NOT NULL CHECK(char_length(btrim(source_title)) BETWEEN 10 AND 200),
 source_publisher text NOT NULL CHECK(char_length(btrim(source_publisher)) BETWEEN 3 AND 160),
 source_uri text NOT NULL CHECK(char_length(source_uri) BETWEEN 12 AND 600),
 source_edition text NOT NULL CHECK(char_length(btrim(source_edition)) BETWEEN 2 AND 100),
 claimed_procedure text NOT NULL CHECK(char_length(btrim(claimed_procedure)) BETWEEN 15 AND 300),
 source_publication_date date NOT NULL,
 source_vintage text NOT NULL CHECK(char_length(btrim(source_vintage)) BETWEEN 4 AND 100),
 source_sha256_claimed bytea NOT NULL CHECK(octet_length(source_sha256_claimed)=32),
 source_rights_reference text NOT NULL CHECK(char_length(btrim(source_rights_reference)) BETWEEN 15 AND 500),
 evidence_note text NOT NULL CHECK(char_length(btrim(evidence_note)) BETWEEN 15 AND 2000),
 period_from timestamptz NOT NULL,
 period_until timestamptz NOT NULL,
 hour_count integer NOT NULL CHECK(hour_count BETWEEN 1 AND 744),
 content_sha256 bytea NOT NULL CHECK(octet_length(content_sha256)=32),
 state text NOT NULL DEFAULT 'CANDIDATE_NOT_ADMITTED'
   CHECK(state='CANDIDATE_NOT_ADMITTED'),
 usable_for_e8 boolean NOT NULL DEFAULT false CHECK(usable_for_e8=false),
 submitted_by uuid NOT NULL REFERENCES app_identity(id),
 submitted_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 CHECK(period_until>period_from),
 UNIQUE(project_id,id),
 UNIQUE(project_id,energy_candidate_id,version),
 FOREIGN KEY(project_id,organization_id,environment)
   REFERENCES app_project(id,organization_id,environment),
 FOREIGN KEY(project_id,energy_candidate_id)
   REFERENCES app_open_e8_energy_candidate(project_id,id)
);
CREATE INDEX app_open_efel_hourly_lookup
 ON app_open_efel_hourly_candidate(project_id,period_code,submitted_at DESC);

CREATE TABLE app_open_efel_hourly_candidate_value (
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 hour_start timestamptz NOT NULL,
 original_value numeric(30,12) NOT NULL CHECK(original_value>=0),
 normalized_value numeric(30,18) NOT NULL CHECK(normalized_value>=0),
 PRIMARY KEY(project_id,candidate_id,hour_start),
 FOREIGN KEY(project_id,candidate_id)
   REFERENCES app_open_efel_hourly_candidate(project_id,id)
);

-- Independent staff review of lineage claims; still NOT normative admission.
CREATE TABLE app_open_efel_hourly_review (
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 version integer NOT NULL CHECK(version BETWEEN 1 AND 100000000),
 outcome text NOT NULL CHECK(outcome IN ('CHECKED_INTERNAL','RETURNED')),
 rationale text NOT NULL CHECK(char_length(btrim(rationale)) BETWEEN 15 AND 2000),
 evidence_reference text NOT NULL CHECK(char_length(btrim(evidence_reference)) BETWEEN 15 AND 600),
 reviewer_id uuid NOT NULL REFERENCES app_identity(id),
 reviewed_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,candidate_id,version),
 FOREIGN KEY(project_id,candidate_id)
   REFERENCES app_open_efel_hourly_candidate(project_id,id)
);
CREATE FUNCTION app_open_efel_independent_review() RETURNS trigger AS $$
DECLARE author_id uuid;
BEGIN
 SELECT submitted_by INTO author_id FROM app_open_efel_hourly_candidate
 WHERE project_id=NEW.project_id AND id=NEW.candidate_id FOR SHARE;
 IF author_id IS NULL OR author_id=NEW.reviewer_id THEN
   RAISE EXCEPTION 'Independent reviewer required for grid factor source';
 END IF;
 RETURN NEW;
END;
$$ LANGUAGE plpgsql;
CREATE TRIGGER app_open_efel_review_guard
 BEFORE INSERT ON app_open_efel_hourly_review
 FOR EACH ROW EXECUTE FUNCTION app_open_efel_independent_review();

CREATE TABLE app_open_efel_hourly_command (
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 command_key text NOT NULL CHECK(command_key ~ '^[A-Za-z0-9._-]{1,128}$'),
 project_id text NOT NULL,
 candidate_id uuid NOT NULL,
 action text NOT NULL CHECK(action IN ('SUBMIT','REVIEW')),
 fingerprint bytea NOT NULL CHECK(octet_length(fingerprint)=32),
 review_version integer,
 PRIMARY KEY(actor_id,command_key),
 FOREIGN KEY(project_id,candidate_id)
   REFERENCES app_open_efel_hourly_candidate(project_id,id),
 FOREIGN KEY(project_id,candidate_id,review_version)
   REFERENCES app_open_efel_hourly_review(project_id,candidate_id,version),
 CHECK((action='SUBMIT' AND review_version IS NULL)
    OR (action='REVIEW' AND review_version IS NOT NULL))
);

CREATE TRIGGER app_open_efel_immutable
 BEFORE UPDATE OR DELETE ON app_open_efel_hourly_candidate
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_efel_value_immutable
 BEFORE UPDATE OR DELETE ON app_open_efel_hourly_candidate_value
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_efel_review_immutable
 BEFORE UPDATE OR DELETE ON app_open_efel_hourly_review
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();
CREATE TRIGGER app_open_efel_command_immutable
 BEFORE UPDATE OR DELETE ON app_open_efel_hourly_command
 FOR EACH ROW EXECUTE FUNCTION app_open_metering_immutable();

ALTER TABLE app_project_technical_access_event
 DROP CONSTRAINT app_project_technical_access_event_action_check;
ALTER TABLE app_project_technical_access_event
 ADD CONSTRAINT app_project_technical_access_event_action_check CHECK(action IN (
    'VIEW_INTAKE','SAVE_INTAKE','VIEW_DOSSIER','PREPARE_DOSSIER','VIEW_CAPABILITIES',
    'VIEW_TRIAGE','SAVE_TRIAGE','MOVE_TRIAGE','APPLY_TRIAGE_EXAMPLE',
    'VIEW_EXPERIENCE','SAVE_EXPERIENCE','VIEW_INVITATION_PLANS','SAVE_INVITATION_PLAN',
    'VIEW_AUTHORITY_REQUESTS','SUBMIT_AUTHORITY_REQUEST','WITHDRAW_AUTHORITY_REQUEST',
    'VIEW_AUTHORITY_DOCUMENTS','ADD_AUTHORITY_DOCUMENT','DOWNLOAD_AUTHORITY_DOCUMENT',
    'VIEW_PROJECT_METADATA','DOWNLOAD_DOSSIER','VIEW_JOURNEY','VIEW_PD_REVIEW',
    'DECIDE_PD_REVIEW','VIEW_GUIDES','SAVE_GUIDE','VIEW_ACTIVE_INVITATIONS',
    'SEND_PROJECT_INVITATION','ACCEPT_PROJECT_INVITATION','REVOKE_PROJECT_INVITATION',
    'VIEW_OPEN_APPLICABILITY','SAVE_OPEN_APPLICABILITY','VIEW_OPEN_CALCULATION',
    'VIEW_OPEN_MONITORING','SAVE_OPEN_MONITORING','SUBMIT_OPEN_MEASUREMENTS',
    'REVIEW_OPEN_MEASUREMENTS','SNAPSHOT_OPEN_PERIOD','VIEW_OPEN_PERIOD_ADMISSION',
    'SUBMIT_OPEN_PERIOD_EVIDENCE','REVIEW_OPEN_PERIOD_EVIDENCE',
    'SUBMIT_OPEN_MONITORING_EVENT','REVIEW_OPEN_MONITORING_EVENT',
    'VIEW_OPEN_METHOD_REVIEW','REVIEW_OPEN_METHOD_REVIEW',
    'VIEW_OPEN_E8_ENERGY_CANDIDATE','PREPARE_OPEN_E8_ENERGY_CANDIDATE',
    'VIEW_OPEN_EFEL_CANDIDATE','SUBMIT_OPEN_EFEL_CANDIDATE','REVIEW_OPEN_EFEL_CANDIDATE'
 ));
