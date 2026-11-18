CREATE TABLE app_quantification_guide_input (
    project_id text NOT NULL,
    guide_id text NOT NULL CHECK (guide_id='quantification'),
    version integer NOT NULL,
    input_id text NOT NULL CHECK (input_id IN ('applicability','baseline','additionality','monitoring')),
    input_version integer NOT NULL,
    PRIMARY KEY (project_id,version,input_id),
    FOREIGN KEY (project_id,guide_id,version) REFERENCES app_technical_guide_revision(project_id,guide_id,version),
    FOREIGN KEY (project_id,input_id,input_version) REFERENCES app_technical_guide_revision(project_id,guide_id,version)
);
