-- Preserve V93 historical opinions; require a linked V94 plan for new initial opinions.
ALTER TABLE app_open_ir_ex_ante_plan ADD CONSTRAINT app_open_ir_ex_ante_review_target
 UNIQUE(project_id,id,version,plan_sha256,author_id);
ALTER TABLE app_open_ir_review
 ADD COLUMN ex_ante_project_id text,
 ADD COLUMN ex_ante_plan_id uuid,
 ADD COLUMN ex_ante_plan_version integer,
 ADD COLUMN ex_ante_plan_sha256 bytea,
 ADD COLUMN ex_ante_plan_author uuid,
 ADD CONSTRAINT app_open_ir_ex_ante_review_fk
 FOREIGN KEY(ex_ante_project_id,ex_ante_plan_id,ex_ante_plan_version,ex_ante_plan_sha256,ex_ante_plan_author)
 REFERENCES app_open_ir_ex_ante_plan(project_id,id,version,plan_sha256,author_id),
 ADD CONSTRAINT app_open_ir_ex_ante_review_shape CHECK (
   (ex_ante_project_id IS NULL AND ex_ante_plan_id IS NULL AND ex_ante_plan_version IS NULL
      AND ex_ante_plan_sha256 IS NULL AND ex_ante_plan_author IS NULL)
   OR (outcome='INITIAL_BASELINE_CHECKED' AND ex_ante_project_id IS NOT NULL
      AND ex_ante_plan_id IS NOT NULL AND ex_ante_plan_version IS NOT NULL
      AND ex_ante_plan_sha256 IS NOT NULL AND ex_ante_plan_author IS NOT NULL
      AND reviewer_id<>ex_ante_plan_author));

CREATE FUNCTION app_open_ir_ex_ante_review_guard() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
 IF NEW.outcome='INITIAL_BASELINE_CHECKED' AND (
   NEW.ex_ante_plan_id IS NULL OR NOT EXISTS (
     SELECT 1 FROM app_open_ir_assessment a
     JOIN app_open_ir_ex_ante_plan p ON p.project_id=a.project_id
     WHERE a.id=NEW.assessment_id AND a.project_id=NEW.ex_ante_project_id
       AND p.id=NEW.ex_ante_plan_id AND p.triage_version=a.triage_version
       AND p.version=(SELECT max(v.version) FROM app_open_ir_ex_ante_plan v WHERE v.project_id=a.project_id)
   )) THEN
   RAISE EXCEPTION 'Initial review requires current project ex ante plan';
 END IF;
 RETURN NEW;
END $$;
CREATE TRIGGER app_open_ir_ex_ante_review_scope
 BEFORE INSERT ON app_open_ir_review
 FOR EACH ROW EXECUTE FUNCTION app_open_ir_ex_ante_review_guard();
