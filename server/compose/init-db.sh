#!/usr/bin/env bash
(
    set -euo pipefail
    export API_PASSWORD AUTH_PASSWORD
    API_PASSWORD=$(< /run/secrets/api_db_password)
    AUTH_PASSWORD=$(< /run/secrets/auth_db_password)
    : "${API_PASSWORD:?Missing API password}" "${AUTH_PASSWORD:?Missing Keycloak password}"
    PGOPTIONS='-c log_min_error_statement=panic' psql -X --set ON_ERROR_STOP=1 --username "$POSTGRES_USER" --dbname postgres <<'SQL'
\getenv api_password API_PASSWORD
\getenv auth_password AUTH_PASSWORD
CREATE ROLE audipp_api LOGIN PASSWORD :'api_password';
CREATE ROLE audipp_auth LOGIN PASSWORD :'auth_password';
CREATE DATABASE audipp_api OWNER audipp_api;
CREATE DATABASE audipp_auth OWNER audipp_auth;
REVOKE CONNECT ON DATABASE audipp_api, audipp_auth, postgres, template1 FROM PUBLIC;
SQL
)
