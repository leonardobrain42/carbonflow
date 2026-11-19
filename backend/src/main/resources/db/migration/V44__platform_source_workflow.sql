-- CF-OPEN-002: platform-owned acquisition work queue, not source ingestion or normative approval.
-- Work status never changes the admitted technical state or license of a source.
CREATE TABLE app_platform_source_work (
    need_code text PRIMARY KEY REFERENCES app_methodology_source_need(code),
    status text NOT NULL CHECK (status IN ('REQUESTED','SOURCING','WAITING_RIGHTS',
                                         'READY_FOR_HANDOFF','ON_HOLD')),
    owner_id uuid REFERENCES app_identity(id),
    created_by uuid NOT NULL REFERENCES app_identity(id),
    version integer NOT NULL DEFAULT 1 CHECK (version > 0),
    created_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    updated_at timestamptz NOT NULL DEFAULT statement_timestamp()
);

CREATE TABLE app_platform_source_work_revision (
    need_code text NOT NULL REFERENCES app_platform_source_work(need_code),
    version integer NOT NULL CHECK (version > 0),
    status text NOT NULL CHECK (status IN ('REQUESTED','SOURCING','WAITING_RIGHTS',
                                         'READY_FOR_HANDOFF','ON_HOLD')),
    owner_id uuid REFERENCES app_identity(id),
    actor_id uuid NOT NULL REFERENCES app_identity(id),
    note text NOT NULL CHECK (char_length(btrim(note)) BETWEEN 1 AND 2000),
    recorded_at timestamptz NOT NULL DEFAULT statement_timestamp(),
    PRIMARY KEY (need_code,version)
);
CREATE INDEX app_platform_source_work_status ON app_platform_source_work(status,updated_at DESC);

-- Explicit audit actions for the source workspace, preserving the previous compliance actions.
ALTER TABLE app_compliance_access_event DROP CONSTRAINT app_compliance_access_event_action_check;
ALTER TABLE app_compliance_access_event ADD CONSTRAINT app_compliance_access_event_action_check
 CHECK (action IN ('MFA_VERIFIED','MFA_DENIED','VIEW_QUEUE','DOWNLOAD_DOCUMENT',
                  'VIEW_PLATFORM_SOURCE_READINESS','VIEW_PLATFORM_SOURCE_CONTEXTS',
                  'VIEW_PLATFORM_SOURCE_WORK','CREATE_PLATFORM_SOURCE_WORK',
                  'UPDATE_PLATFORM_SOURCE_WORK','VIEW_PLATFORM_SOURCE_WORK_HISTORY'));
