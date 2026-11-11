#!/bin/sh
# Runs once in this project's dedicated PostgreSQL volume, as its administrator.
set -eu
: "${OWLIBI_MIGRATION_PASSWORD:?missing migration password}"
: "${OWLIBI_RUNTIME_PASSWORD:?missing runtime password}"
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set ON_ERROR_STOP=1 \
  --set migration_password="$OWLIBI_MIGRATION_PASSWORD" --set runtime_password="$OWLIBI_RUNTIME_PASSWORD" <<'SQL'
CREATE ROLE owlibi_migrator LOGIN PASSWORD :'migration_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
CREATE ROLE owlibi_runtime LOGIN PASSWORD :'runtime_password' NOSUPERUSER NOCREATEDB NOCREATEROLE NOREPLICATION;
SELECT format('REVOKE ALL ON DATABASE %I FROM PUBLIC', current_database()) \gexec
SELECT format('GRANT CONNECT ON DATABASE %I TO owlibi_migrator, owlibi_runtime', current_database()) \gexec
REVOKE ALL ON SCHEMA public FROM PUBLIC;
CREATE SCHEMA owlibi AUTHORIZATION owlibi_migrator;
GRANT USAGE ON SCHEMA owlibi TO owlibi_runtime;
ALTER ROLE owlibi_migrator SET search_path = owlibi;
ALTER ROLE owlibi_runtime SET search_path = owlibi;
ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator IN SCHEMA owlibi GRANT SELECT, INSERT, UPDATE, DELETE ON TABLES TO owlibi_runtime;
ALTER DEFAULT PRIVILEGES FOR ROLE owlibi_migrator IN SCHEMA owlibi GRANT USAGE, SELECT ON SEQUENCES TO owlibi_runtime;
SQL
