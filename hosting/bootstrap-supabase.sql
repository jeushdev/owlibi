-- Run ONCE in the SQL editor of a NEW dedicated Supabase project, as postgres.
-- Replace both placeholders with different randomly generated passwords first.
-- This changes only Owlibi roles/schema; it does not revoke Supabase platform privileges.
BEGIN;
DO $$
BEGIN
    IF 'REPLACE_WITH_RANDOM_MIGRATION_PASSWORD' LIKE 'REPLACE_WITH_%'
       OR 'REPLACE_WITH_RANDOM_RUNTIME_PASSWORD' LIKE 'REPLACE_WITH_%' THEN
        RAISE EXCEPTION 'Replace both password placeholders before running this script';
    END IF;
END $$;
CREATE ROLE owlibi_migrator LOGIN PASSWORD 'REPLACE_WITH_RANDOM_MIGRATION_PASSWORD'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE owlibi_runtime LOGIN PASSWORD 'REPLACE_WITH_RANDOM_RUNTIME_PASSWORD'
    NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
GRANT owlibi_migrator TO postgres;
CREATE SCHEMA owlibi AUTHORIZATION owlibi_migrator;
REVOKE ALL ON SCHEMA owlibi FROM PUBLIC;
GRANT CONNECT ON DATABASE postgres TO owlibi_migrator, owlibi_runtime;
GRANT USAGE ON SCHEMA owlibi TO owlibi_runtime;
ALTER ROLE owlibi_migrator SET search_path = owlibi;
ALTER ROLE owlibi_runtime SET search_path = owlibi;
ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator IN SCHEMA owlibi
    GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO owlibi_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator IN SCHEMA owlibi
    GRANT USAGE, SELECT ON SEQUENCES TO owlibi_runtime;
COMMIT;
