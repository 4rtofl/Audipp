#!/usr/bin/env bash
set -euo pipefail
umask 077

here=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
keycloak_dist=${1:?Specify the extracted Keycloak 26.7.5 distribution}
api_dist="$here/../build/install/audipp-server/bin/audipp-server"
pg_bin=${PG_BIN:-$(pg_config --bindir)}
for binary in "$keycloak_dist/bin/kc.sh" "$api_dist" "$pg_bin/initdb" "$pg_bin/pg_ctl"; do
    [[ -x "$binary" ]] || { printf 'Executable missing: %s\n' "$binary" >&2; exit 1; }
done

fixture=$(mktemp -d)
kc_pid=
api_pid=
cleanup() {
    local status=$?
    trap - EXIT
    for pid in "$kc_pid" "$api_pid"; do
        if [[ -n "$pid" ]]; then
            kill "$pid" 2>/dev/null || true
            wait "$pid" 2>/dev/null || true
        fi
    done
    if [[ -f "$fixture/pg/postmaster.pid" ]]; then
        "$pg_bin/pg_ctl" -D "$fixture/pg" -m fast stop >/dev/null || status=1
    fi
    if (( status == 0 )); then
        rm -rf -- "$fixture"
    else
        printf 'Local fixture logs: %s\n' "$fixture" >&2
    fi
    exit "$status"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

ports=$(python3 - <<'PY'
import socket
from contextlib import ExitStack

with ExitStack() as stack:
    sockets = [stack.enter_context(socket.socket()) for _ in range(4)]
    for listener in sockets:
        listener.bind(("127.0.0.1", 0))
    print(*(listener.getsockname()[1] for listener in sockets))
PY
)
read -r kc_port kc_http_port api_port pg_port <<< "$ports"
kc_origin="https://127.0.0.1:$kc_port"
api_origin="https://127.0.0.1:$api_port"

mkdir "$fixture/keycloak"
cp -a -- "$keycloak_dist"/{bin,conf,lib,providers,themes} "$fixture/keycloak/"
openssl req -x509 -newkey rsa:2048 -nodes -keyout "$fixture/key.pem" -out "$fixture/cert.pem" \
    -days 1 -subj '/CN=127.0.0.1' -addext 'subjectAltName=IP:127.0.0.1' >"$fixture/tls.log" 2>&1
openssl pkcs12 -export -inkey "$fixture/key.pem" -in "$fixture/cert.pem" -name audipp-fixture \
    -out "$fixture/api.p12" -passout pass:fixture-store-only
keytool -importcert -noprompt -alias audipp-fixture -file "$fixture/cert.pem" \
    -keystore "$fixture/trust.p12" -storepass fixture-store-only >>"$fixture/tls.log" 2>&1

KC_BOOTSTRAP_ADMIN_USERNAME=fixture-admin KC_BOOTSTRAP_ADMIN_PASSWORD=fixture-admin-only \
    KC_TRUSTSTORE_PATHS="$fixture/cert.pem" "$fixture/keycloak/bin/kc.sh" start-dev --db=dev-mem \
    --hostname="$kc_origin" --http-host=127.0.0.1 --http-port="$kc_http_port" --https-port="$kc_port" \
    --https-certificate-file="$fixture/cert.pem" --https-certificate-key-file="$fixture/key.pem" \
    >"$fixture/keycloak.log" 2>&1 &
kc_pid=$!

"$pg_bin/initdb" -D "$fixture/pg" -U postgres --auth-local=trust --auth-host=scram-sha-256 \
    --no-locale >"$fixture/initdb.log" 2>&1
"$pg_bin/pg_ctl" -D "$fixture/pg" -l "$fixture/postgres.log" \
    -o "-h 127.0.0.1 -p $pg_port -k $fixture" -w start >/dev/null
psql -X -q -v ON_ERROR_STOP=1 -h "$fixture" -p "$pg_port" -U postgres postgres <<'SQL'
CREATE ROLE fixture_business LOGIN PASSWORD 'fixture-db-only';
CREATE ROLE fixture_auth LOGIN;
CREATE DATABASE fixture_business OWNER fixture_business;
CREATE DATABASE fixture_auth OWNER fixture_auth;
REVOKE CONNECT ON DATABASE fixture_auth FROM PUBLIC;
SQL

AUDIPP_DB_HOST=127.0.0.1 AUDIPP_DB_PORT="$pg_port" AUDIPP_DB_NAME=fixture_business \
    AUDIPP_DB_USER=fixture_business AUDIPP_DB_PASSWORD=fixture-db-only AUDIPP_DB_SSLMODE=disable \
    AUDIPP_AUTH_DB_NAME=fixture_auth AUDIPP_AUTH_DB_USER=fixture_auth \
    AUDIPP_ISSUER="$kc_origin/realms/audipp" AUDIPP_AUDIENCE=audipp-api \
    AUDIPP_TLS_STORE="$fixture/api.p12" AUDIPP_TLS_PASSWORD=fixture-store-only \
    AUDIPP_TLS_ALIAS=audipp-fixture AUDIPP_BIND_HOST=127.0.0.1 AUDIPP_TLS_PORT="$api_port" \
    JAVA_OPTS="-Djavax.net.ssl.trustStore=$fixture/trust.p12 -Djavax.net.ssl.trustStorePassword=fixture-store-only" \
    "$api_dist" >"$fixture/api.log" 2>&1 &
api_pid=$!

ready="$kc_origin/realms/master/.well-known/openid-configuration"
for ((i = 0; i < 60; i++)); do
    kill -0 "$kc_pid" "$api_pid"
    if curl --noproxy '*' --cacert "$fixture/cert.pem" --fail --silent --max-time 2 "$ready" >/dev/null; then
        break
    fi
    sleep 1
done
python3 -B "$here/test_invite_recovery.py" \
    --keycloak "$kc_origin" --api "$api_origin" --ca "$fixture/cert.pem"
if grep -qE 'fixture-(initial-secret|changed-secret|recovery-secret|provider-secret|client-secret|admin-only)|eyJ[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+\.[A-Za-z0-9_-]+|redirect_uri=' \
    "$fixture/keycloak.log" "$fixture/api.log"; then
    printf 'Fixture credentials, tokens, or OAuth redirect URIs appeared in application logs\n' >&2
    exit 1
fi
