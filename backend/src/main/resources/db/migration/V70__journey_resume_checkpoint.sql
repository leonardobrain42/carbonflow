-- CF-UX-TECH-001 / UX-07. Personal, project-scoped resume pointer.
-- This is navigation state ONLY; it never alters canonical project facts, proofs,
-- technical review, PD versions, tasks, methodology, or their audit history.
CREATE TABLE app_project_journey_checkpoint (
 project_id text NOT NULL REFERENCES app_project(id),
 actor_id uuid NOT NULL REFERENCES app_identity(id),
 area text NOT NULL CHECK(area IN ('triage','technical')),
 screen text,
 guide_id text,
 step_id text,
 updated_at timestamptz NOT NULL DEFAULT statement_timestamp(),
 PRIMARY KEY(project_id,actor_id),
 CHECK ((area='triage' AND screen IS NULL AND guide_id IS NULL AND step_id IS NULL)
     OR (area='technical' AND screen IS NOT NULL AND char_length(screen) BETWEEN 2 AND 40)),
 CHECK (guide_id IS NULL OR char_length(guide_id) BETWEEN 1 AND 80),
 CHECK (step_id IS NULL OR char_length(step_id) BETWEEN 1 AND 40)
);
CREATE INDEX app_project_journey_checkpoint_actor ON app_project_journey_checkpoint(actor_id,updated_at DESC);
