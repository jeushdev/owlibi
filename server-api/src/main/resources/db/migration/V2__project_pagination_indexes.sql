-- Additive A2 indexes; V1 and existing data/roles remain unchanged.
CREATE INDEX project_list_cursor ON projects(created_at_utc DESC,id DESC);
CREATE INDEX invitation_outstanding ON project_invitations(project_id,expires_at_utc) WHERE accepted_at_utc IS NULL;
