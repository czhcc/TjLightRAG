#!/usr/bin/env bash
set -euo pipefail
: "${APP_DB_PASSWORD:?Set APP_DB_PASSWORD for the restricted runtime role}"
# psql literals are quoted with format(%L), never interpolated as SQL source.
psql --username "$POSTGRES_USER" --dbname "$POSTGRES_DB" --set=ON_ERROR_STOP=1 --set=runtime_password="$APP_DB_PASSWORD" <<'SQL'
SELECT FORMAT('CREATE ROLE tjlightrag_app LOGIN PASSWORD %L', :'runtime_password')
WHERE NOT EXISTS (SELECT 1 FROM pg_roles WHERE rolname = 'tjlightrag_app')
\gexec
SQL
