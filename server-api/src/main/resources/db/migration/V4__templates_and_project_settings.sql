-- R1 additive storage: old Blank snapshots/tasks/events remain unchanged; no guessed rules.
ALTER TABLE projects
    ADD COLUMN settings_version BIGINT NOT NULL DEFAULT 0 CHECK (settings_version >= 0),
    ADD COLUMN effort_scale_name VARCHAR(100),
    ADD COLUMN effort_scale_examples JSONB,
    ADD COLUMN overload_threshold NUMERIC(10,2),
    ADD COLUMN near_due_days INTEGER,
    ADD COLUMN settings_updated_at_utc TIMESTAMPTZ;

CREATE FUNCTION valid_project_rules(scale_name TEXT, examples JSONB, threshold NUMERIC, days INTEGER)
RETURNS BOOLEAN LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog AS $$
DECLARE item JSONB; point NUMERIC; seen NUMERIC[] := '{}';
BEGIN
    IF scale_name IS NULL OR length(scale_name) NOT BETWEEN 1 AND 100 OR length(btrim(scale_name)) = 0
       OR threshold IS NULL OR threshold NOT BETWEEN 0.01 AND 99999999.99
       OR days IS NULL OR days NOT BETWEEN 0 AND 30 OR examples IS NULL THEN RETURN FALSE; END IF;
    IF jsonb_typeof(examples) <> 'array' THEN RETURN FALSE; END IF;
    IF jsonb_array_length(examples) NOT BETWEEN 1 AND 10 THEN RETURN FALSE; END IF;
    FOR item IN SELECT value FROM jsonb_array_elements(examples) LOOP
        IF jsonb_typeof(item) <> 'object' THEN RETURN FALSE; END IF;
        IF (SELECT count(*) FROM jsonb_object_keys(item)) <> 2 OR NOT item ?& ARRAY['points','label'] THEN RETURN FALSE; END IF;
        IF jsonb_typeof(item->'points') <> 'string' OR jsonb_typeof(item->'label') <> 'string' THEN RETURN FALSE; END IF;
        IF item->>'points' !~ '^[0-9]{1,8}\.[0-9]{2}$' OR length(item->>'label') NOT BETWEEN 1 AND 100 OR length(btrim(item->>'label')) = 0 THEN RETURN FALSE; END IF;
        point := (item->>'points')::NUMERIC;
        IF point NOT BETWEEN 0.01 AND 99999999.99 OR point = ANY(seen) THEN RETURN FALSE; END IF;
        seen := array_append(seen,point);
    END LOOP;
    RETURN TRUE;
END $$;

CREATE FUNCTION valid_project_rules_json(rules JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog AS $$
BEGIN
    IF rules IS NULL OR jsonb_typeof(rules) <> 'object' THEN RETURN FALSE; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(rules)) <> 4 OR NOT rules ?& ARRAY['effortScaleName','effortScaleExamples','overloadThreshold','nearDueDays'] THEN RETURN FALSE; END IF;
    IF jsonb_typeof(rules->'effortScaleName') <> 'string' OR jsonb_typeof(rules->'overloadThreshold') <> 'string' OR jsonb_typeof(rules->'nearDueDays') <> 'number' THEN RETURN FALSE; END IF;
    IF rules->>'overloadThreshold' !~ '^[0-9]{1,8}\.[0-9]{2}$' OR rules->>'nearDueDays' !~ '^([0-9]|[12][0-9]|30)$' THEN RETURN FALSE; END IF;
    RETURN owlibi.valid_project_rules(rules->>'effortScaleName',rules->'effortScaleExamples',(rules->>'overloadThreshold')::NUMERIC,(rules->>'nearDueDays')::INTEGER);
END $$;

ALTER TABLE projects ADD CONSTRAINT project_settings_shape CHECK (
    (settings_version = 0 AND effort_scale_name IS NULL AND effort_scale_examples IS NULL AND overload_threshold IS NULL AND near_due_days IS NULL AND settings_updated_at_utc IS NULL)
    OR (settings_version > 0 AND settings_updated_at_utc IS NOT NULL AND valid_project_rules(effort_scale_name,effort_scale_examples,overload_threshold,near_due_days))
);

CREATE FUNCTION valid_template_snapshot(snapshot JSONB) RETURNS BOOLEAN
LANGUAGE plpgsql IMMUTABLE SET search_path = pg_catalog AS $$
DECLARE template JSONB; row JSONB; category JSONB; categories TEXT[] := '{}'; index INTEGER := 0;
BEGIN
    IF snapshot = '{"id":"blank","version":1}'::JSONB THEN RETURN TRUE; END IF;
    IF snapshot IS NULL OR jsonb_typeof(snapshot) <> 'object' THEN RETURN FALSE; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(snapshot)) <> 3 OR NOT snapshot ?& ARRAY['schemaVersion','template','effectiveSettings'] OR snapshot->'schemaVersion' <> '1'::JSONB THEN RETURN FALSE; END IF;
    template := snapshot->'template';
    IF jsonb_typeof(template) <> 'object' THEN RETURN FALSE; END IF;
    IF (SELECT count(*) FROM jsonb_object_keys(template)) <> 7 OR NOT template ?& ARRAY['id','name','version','categories','starterTasks','suggestedSettings','initialization'] THEN RETURN FALSE; END IF;
    IF jsonb_typeof(template->'id') <> 'string' OR template->>'id' NOT IN ('blank','coding','written','reporting-presentation','filming-video') OR jsonb_typeof(template->'name') <> 'string' THEN RETURN FALSE; END IF;
    IF length(template->>'name') NOT BETWEEN 1 AND 200 OR length(btrim(template->>'name')) = 0 THEN RETURN FALSE; END IF;
    IF jsonb_typeof(template->'version') <> 'number' OR template->>'version' !~ '^[1-9][0-9]{0,9}$' THEN RETURN FALSE; END IF;
    IF (template->>'version')::BIGINT > 2147483647 OR (template->>'id' = 'blank' AND (template->>'version')::BIGINT < 2) THEN RETURN FALSE; END IF;
    IF jsonb_typeof(template->'categories') <> 'array' OR jsonb_typeof(template->'starterTasks') <> 'array' THEN RETURN FALSE; END IF;
    IF template->'initialization' <> '{"assigneePolicy":"CREATOR_ONLY","acceptancePolicy":"PENDING"}'::JSONB OR NOT owlibi.valid_project_rules_json(template->'suggestedSettings') THEN RETURN FALSE; END IF;
    IF snapshot->'effectiveSettings' <> 'null'::JSONB AND NOT owlibi.valid_project_rules_json(snapshot->'effectiveSettings') THEN RETURN FALSE; END IF;
    IF template->>'id' <> 'blank' AND snapshot->'effectiveSettings' = 'null'::JSONB THEN RETURN FALSE; END IF;
    FOR category IN SELECT value FROM jsonb_array_elements(template->'categories') LOOP
        IF jsonb_typeof(category) <> 'string' THEN RETURN FALSE; END IF;
        IF length(category#>>'{}') NOT BETWEEN 1 AND 100 OR length(btrim(category#>>'{}')) = 0 OR category#>>'{}' = ANY(categories) THEN RETURN FALSE; END IF;
        categories := array_append(categories,category#>>'{}');
    END LOOP;
    IF template->>'id' = 'blank' THEN RETURN cardinality(categories) = 0 AND jsonb_array_length(template->'starterTasks') = 0; END IF;
    IF jsonb_array_length(template->'starterTasks') <> 5 THEN RETURN FALSE; END IF;
    FOR row IN SELECT value FROM jsonb_array_elements(template->'starterTasks') LOOP
        index := index + 1;
        IF jsonb_typeof(row) <> 'object' THEN RETURN FALSE; END IF;
        IF (SELECT count(*) FROM jsonb_object_keys(row)) <> 6 OR NOT row ?& ARRAY['key','title','description','category','effortPoints','dueDate'] THEN RETURN FALSE; END IF;
        IF jsonb_typeof(row->'key') <> 'string' OR row->>'key' <> 'task-'||lpad(index::TEXT,2,'0') OR row->'dueDate' <> 'null'::JSONB THEN RETURN FALSE; END IF;
        IF jsonb_typeof(row->'title') <> 'string' OR jsonb_typeof(row->'description') <> 'string' OR jsonb_typeof(row->'category') <> 'string' OR jsonb_typeof(row->'effortPoints') <> 'string' THEN RETURN FALSE; END IF;
        IF length(row->>'title') NOT BETWEEN 1 AND 200 OR length(btrim(row->>'title')) = 0 OR length(row->>'description') > 4000 OR NOT row->>'category' = ANY(categories) THEN RETURN FALSE; END IF;
        IF row->>'effortPoints' !~ '^[0-9]{1,8}\.[0-9]{2}$' THEN RETURN FALSE; END IF;
        IF (row->>'effortPoints')::NUMERIC NOT BETWEEN 0.01 AND 99999999.99 THEN RETURN FALSE; END IF;
    END LOOP;
    RETURN TRUE;
END $$;

ALTER TABLE projects DROP CONSTRAINT projects_template_snapshot_check;
ALTER TABLE projects ADD CONSTRAINT projects_template_snapshot_check CHECK (valid_template_snapshot(template_snapshot));

CREATE FUNCTION preserve_template_snapshot() RETURNS TRIGGER
LANGUAGE plpgsql SET search_path = pg_catalog AS $$
BEGIN
    IF NEW.template_snapshot IS DISTINCT FROM OLD.template_snapshot THEN
        RAISE EXCEPTION 'Project template snapshot is immutable' USING ERRCODE = '23514';
    END IF;
    RETURN NEW;
END $$;
CREATE TRIGGER project_template_snapshot_immutable BEFORE UPDATE OF template_snapshot ON projects
FOR EACH ROW EXECUTE FUNCTION preserve_template_snapshot();
