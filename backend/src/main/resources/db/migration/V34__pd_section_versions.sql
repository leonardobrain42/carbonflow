CREATE TABLE app_pd_section_version (
    project_id text NOT NULL,
    section_id text NOT NULL,
    version integer NOT NULL CHECK(version > 0),
    guide_version integer NOT NULL,
    author_id uuid NOT NULL REFERENCES app_identity(id),
    narrative text NOT NULL,
    artifact text NOT NULL,
    sha256 char(64) NOT NULL,
    PRIMARY KEY(project_id,section_id,version),
    FOREIGN KEY(project_id,section_id,guide_version)
        REFERENCES app_technical_guide_revision(project_id,guide_id,version)
);
