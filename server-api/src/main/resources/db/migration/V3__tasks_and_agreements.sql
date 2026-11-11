-- Additive A3 only. No M2 field edits, M3 settings or development-data reset.
CREATE TABLE tasks (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    title VARCHAR(200) NOT NULL CHECK (length(btrim(title)) > 0),
    description VARCHAR(4000) NOT NULL DEFAULT '',
    category VARCHAR(100) NOT NULL DEFAULT '',
    status VARCHAR(11) NOT NULL CHECK (status IN ('TODO','IN_PROGRESS','REVIEW','DONE','CANCELLED')),
    due_date DATE CHECK (due_date BETWEEN DATE '0001-01-01 BC' AND DATE '9999-12-31'),
    effort_points NUMERIC(10,2) NOT NULL CHECK (effort_points BETWEEN 0.01 AND 99999999.99),
    agreement_version BIGINT NOT NULL CHECK (agreement_version >= 1),
    version BIGINT NOT NULL CHECK (version >= 0),
    first_started_at_utc TIMESTAMPTZ,
    created_by UUID NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    updated_at_utc TIMESTAMPTZ NOT NULL,
    UNIQUE (id,project_id),
    FOREIGN KEY (project_id,created_by) REFERENCES project_memberships(project_id,user_id)
);
CREATE TABLE task_assignees (
    task_id UUID NOT NULL,
    project_id UUID NOT NULL,
    user_id UUID NOT NULL,
    assigned_at_utc TIMESTAMPTZ NOT NULL,
    accepted_agreement_version BIGINT CHECK (accepted_agreement_version >= 1),
    accepted_at_utc TIMESTAMPTZ,
    PRIMARY KEY (task_id,user_id),
    FOREIGN KEY (task_id,project_id) REFERENCES tasks(id,project_id),
    FOREIGN KEY (project_id,user_id) REFERENCES project_memberships(project_id,user_id),
    CHECK ((accepted_agreement_version IS NULL) = (accepted_at_utc IS NULL))
);
CREATE INDEX task_board_order ON tasks(project_id,created_at_utc,id);
CREATE INDEX task_assignee_project ON task_assignees(project_id,task_id,user_id);
ALTER TABLE activity_events ADD CONSTRAINT activity_task_project
    FOREIGN KEY (task_id,project_id) REFERENCES tasks(id,project_id);
