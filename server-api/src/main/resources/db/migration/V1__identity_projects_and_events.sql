-- A1 foundation only. Task schema/workflow comes in A3; settings/templates in M3.
CREATE TABLE users (
    id UUID PRIMARY KEY,
    normalized_email VARCHAR(254) NOT NULL UNIQUE,
    display_name VARCHAR(100) NOT NULL CHECK (length(btrim(display_name)) > 0),
    password_hash VARCHAR(100) NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    CHECK (normalized_email = lower(btrim(normalized_email)) AND length(normalized_email) > 0)
);
CREATE TABLE projects (
    id UUID PRIMARY KEY,
    name VARCHAR(200) NOT NULL CHECK (length(btrim(name)) > 0),
    description VARCHAR(4000) NOT NULL DEFAULT '',
    leader_id UUID NOT NULL REFERENCES users(id),
    leader_role VARCHAR(6) NOT NULL DEFAULT 'LEADER' CHECK (leader_role = 'LEADER'),
    template_snapshot JSONB NOT NULL CHECK (template_snapshot = '{"id":"blank","version":1}'::jsonb),
    project_zone VARCHAR(50) NOT NULL DEFAULT 'Asia/Manila' CHECK (project_zone = 'Asia/Manila'),
    created_at_utc TIMESTAMPTZ NOT NULL
);
CREATE TABLE project_memberships (
    project_id UUID NOT NULL REFERENCES projects(id),
    user_id UUID NOT NULL REFERENCES users(id),
    role VARCHAR(6) NOT NULL CHECK (role IN ('LEADER','MEMBER')),
    joined_at_utc TIMESTAMPTZ NOT NULL,
    PRIMARY KEY (project_id, user_id),
    UNIQUE (project_id, user_id, role)
);
CREATE UNIQUE INDEX one_leader_per_project ON project_memberships(project_id) WHERE role = 'LEADER';
ALTER TABLE projects ADD CONSTRAINT project_leader_membership FOREIGN KEY (id,leader_id,leader_role)
    REFERENCES project_memberships(project_id,user_id,role) DEFERRABLE INITIALLY DEFERRED;
CREATE INDEX membership_projects ON project_memberships(user_id,project_id);
CREATE TABLE project_invitations (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    token_hash BYTEA NOT NULL UNIQUE CHECK (octet_length(token_hash) = 32),
    created_by UUID NOT NULL,
    created_at_utc TIMESTAMPTZ NOT NULL,
    expires_at_utc TIMESTAMPTZ NOT NULL CHECK (expires_at_utc > created_at_utc),
    accepted_at_utc TIMESTAMPTZ,
    accepted_by UUID REFERENCES users(id),
    FOREIGN KEY (project_id,created_by) REFERENCES project_memberships(project_id,user_id),
    CHECK ((accepted_at_utc IS NULL) = (accepted_by IS NULL)),
    CHECK (accepted_at_utc IS NULL OR (accepted_at_utc >= created_at_utc AND accepted_at_utc < expires_at_utc))
);
CREATE INDEX invitation_project_expiry ON project_invitations(project_id,expires_at_utc);
CREATE TABLE activity_events (
    id UUID PRIMARY KEY,
    project_id UUID NOT NULL REFERENCES projects(id),
    task_id UUID, -- A3 will add the task foreign key together with the task schema.
    actor_id UUID NOT NULL,
    event_type VARCHAR(100) NOT NULL CHECK (length(event_type) > 0),
    occurred_at_utc TIMESTAMPTZ NOT NULL,
    reason VARCHAR(1000),
    change_summary JSONB NOT NULL CHECK (jsonb_typeof(change_summary) = 'object'),
    FOREIGN KEY (project_id,actor_id) REFERENCES project_memberships(project_id,user_id)
);
CREATE INDEX activity_project_cursor ON activity_events(project_id,occurred_at_utc DESC,id DESC);
-- Flyway history exists before V1; narrow default DML for special tables explicitly.
REVOKE ALL ON flyway_schema_history FROM owlibi_runtime;
GRANT SELECT ON flyway_schema_history TO owlibi_runtime;
REVOKE ALL ON activity_events FROM owlibi_runtime;
GRANT SELECT, INSERT ON activity_events TO owlibi_runtime;
