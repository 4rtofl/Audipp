#!/usr/bin/env bash
set -euo pipefail

if [[ $# != 2 || ! $1 =~ ^(dump|restore)$ || ! $2 =~ ^(api|auth)$ ]]; then
    printf >&2 'Usage: worker {dump|restore} {api|auth}\n'
    exit 2
fi

export PGUSER="audipp_$2" PGDATABASE="audipp_$2" PGPASSWORD
PGPASSWORD=$(< "/run/secrets/${2}_db_password")
if [[ $1 == dump ]]; then
    exec pg_dump --no-password --format=custom
fi
# With no filename pg_restore reads stdin, which must be seekable for some archive operations.
archive=$(mktemp)
trap 'rm -f -- "$archive"' EXIT
cat > "$archive"
pg_restore --no-password --dbname "$PGDATABASE" --single-transaction "$archive"
