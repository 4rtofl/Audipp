#!/usr/bin/env bash
set -euo pipefail
umask 077

compose_dir=$(cd -- "$(dirname -- "${BASH_SOURCE[0]}")" && pwd)
server_dir=$(dirname -- "$compose_dir")
socket=${AUDIPP_DOCKER_SOCKET:-/var/run/docker.sock}
unset DOCKER_CONTEXT DOCKER_HOST COMPOSE_PROFILES
docker_local() { docker --host "unix://$socket" "$@"; }
engine_version=$(docker_local version --format '{{.Server.Version}}')
if [[ ! $engine_version =~ ^([0-9]+)\. ]] || (( BASH_REMATCH[1] < 28 )); then
    printf >&2 'Docker Engine 28.0 or later is required for loopback-only published ports.\n'
    exit 1
fi

export AUDIPP_SECRETS_DIR
AUDIPP_SECRETS_DIR=$("$compose_dir/create-local-secrets.sh")
project="audipp-smoke-${AUDIPP_SECRETS_DIR##*.}"
project=${project,,}
original_project=$project
restore_project="$project-restore"
export AUDIPP_API_IMAGE="$project-api:local" AUDIPP_AUTH_PORT=0 AUDIPP_API_PORT=0

compose() {
    docker_local compose -p "$project" -f "$compose_dir/compose.yaml" \
        -f "$AUDIPP_SECRETS_DIR/smoke.yaml" "$@"
}
cleanup() {
    local result=$?
    trap - EXIT
    set +e
    for project in "$original_project" "$restore_project"; do
        if (( result != 0 )); then
            compose ps -a >&2
            compose logs --no-color --tail 35 db keycloak api >&2
        fi
        compose --profile maintenance down --volumes || result=1
    done
    if docker_local image inspect "$AUDIPP_API_IMAGE" >/dev/null 2>&1; then
        docker_local image rm "$AUDIPP_API_IMAGE" >/dev/null || result=1
    fi
    find "$AUDIPP_SECRETS_DIR" -depth -delete
    exit "$result"
}
trap cleanup EXIT
trap 'exit 130' INT
trap 'exit 143' TERM

# This disposable service-account realm exercises OIDC/JWKS without using the invitation realm.
openssl rand -hex 32 | tr -d '\n' > "$AUDIPP_SECRETS_DIR/client-secret"
python3 - "$AUDIPP_SECRETS_DIR" <<'PY'
import json
import sys
from pathlib import Path

directory = Path(sys.argv[1])
realm = {
    "realm": "audipp",
    "enabled": True,
    "accessTokenLifespan": 300,
    "clients": [{
        "clientId": "compose-smoke",
        "enabled": True,
        "secret": (directory / "client-secret").read_text(),
        "clientAuthenticatorType": "client-secret",
        "serviceAccountsEnabled": True,
        "standardFlowEnabled": False,
        "directAccessGrantsEnabled": False,
        "protocolMappers": [{
            "name": "api audience",
            "protocol": "openid-connect",
            "protocolMapper": "oidc-audience-mapper",
            "config": {"included.custom.audience": "audipp-api", "access.token.claim": "true"},
        }],
    }],
}
(directory / "audipp-realm.json").write_text(json.dumps(realm))
PY
chmod 444 "$AUDIPP_SECRETS_DIR/audipp-realm.json"
cat > "$AUDIPP_SECRETS_DIR/smoke.yaml" <<'YAML'
services:
  keycloak:
    command: [start, --import-realm]
    volumes:
      - type: bind
        source: ${AUDIPP_SECRETS_DIR}/audipp-realm.json
        target: /opt/keycloak/data/import/audipp-realm.json
        read_only: true
        bind:
          create_host_path: false
YAML
cat > "$AUDIPP_SECRETS_DIR/broken.yaml" <<'YAML'
services:
  api:
    environment:
      AUDIPP_TLS_STORE: /run/secrets/missing.p12
YAML

docker_local build --platform linux/amd64 -f "$compose_dir/Dockerfile" -t "$AUDIPP_API_IMAGE" \
    "$server_dir/build/install/audipp-server"
compose up -d --wait --wait-timeout 210
port() {
    local binding
    binding=$(compose port "$1" "$2")
    [[ $binding =~ ^127\.0\.0\.1:([1-9][0-9]*)$ ]] || return 1
    printf '%s\n' "${BASH_REMATCH[1]}"
}
refresh_ports() {
    auth_port=$(port keycloak 8443)
    api_port=$(port api 9443)
}
refresh_ports

https=(curl --noproxy '*' --silent --show-error --max-time 8)
tls=("${https[@]}" --cacert "$AUDIPP_SECRETS_DIR/ca.crt")
issuer=https://auth.audipp.test:8443/realms/audipp
api=https://api.audipp.test:9443/v1/me
oidc() { "${tls[@]}" --connect-to "auth.audipp.test:8443:127.0.0.1:$auth_port" --fail "$@"; }
get_token() {
    oidc --data 'grant_type=client_credentials&client_id=compose-smoke' \
        --data-urlencode "client_secret@$AUDIPP_SECRETS_DIR/client-secret" \
        "$issuer/protocol/openid-connect/token" | python3 -c 'import json,sys; print(json.load(sys.stdin)["access_token"])'
}
get_api() {
    local expected=$1 status
    shift
    status=$("${tls[@]}" --connect-to "api.audipp.test:9443:127.0.0.1:$api_port" \
        -o "$AUDIPP_SECRETS_DIR/response.json" -w '%{http_code}' "$@" "$api")
    if [[ $status != "$expected" ]]; then
        printf >&2 'API returned HTTP %s, expected %s.\n' "$status" "$expected"
        return 1
    fi
    cat "$AUDIPP_SECRETS_DIR/response.json"
}
get_authenticated_api() {
    printf 'header = "Authorization: Bearer %s"\n' "$token" | get_api "$1" --config -
}

printf 'Checking loopback ports, TLS trust, issuer and ownership.\n'
for service in db keycloak api; do
    container=$(compose ps -q "$service")
    bindings=$(docker_local inspect --format '{{len .HostConfig.PortBindings}}' "$container")
    expected=1
    if [[ $service == db ]]; then
        expected=0
    fi
    if [[ $bindings != "$expected" ]]; then
        printf >&2 '%s has %s published port bindings, expected %s.\n' "$service" "$bindings" "$expected"
        exit 1
    fi
done
management_result=0
compose exec -T api curl --noproxy '*' --connect-timeout 2 --max-time 4 \
    --silent --show-error --output /dev/null \
    http://auth.audipp.test:9000/health/ready || management_result=$?
if [[ $management_result != 7 ]]; then
    printf >&2 'Keycloak management HTTP probe returned %s, expected curl exit code 7.\n' "$management_result"
    exit 1
fi
tls_result=0
"${https[@]}" --connect-to "api.audipp.test:9443:127.0.0.1:$api_port" \
    -o /dev/null "$api" 2>/dev/null || tls_result=$?
[[ $tls_result == 60 ]]
oidc "$issuer/.well-known/openid-configuration" | python3 -c \
    'import json,sys; assert json.load(sys.stdin)["issuer"] == sys.argv[1]' "$issuer"
[[ $(get_api 401) == '{"error":"unauthorized"}' ]]
token=$(get_token)
owner=$(get_authenticated_api 200)
python3 -c 'import json,sys,uuid; uuid.UUID(json.loads(sys.argv[1])["user_id"])' "$owner"

printf 'Checking database separation, interruption and retry.\n'
if denial=$(compose exec -T db bash -ec '
    PGPASSWORD=$(cat /run/secrets/api_db_password)
    export PGPASSWORD
    psql -wX -h 127.0.0.1 -U audipp_api -d audipp_auth -c "SELECT 1"
' 2>&1); then
    printf >&2 'The API role unexpectedly connected to the authentication database.\n'
    exit 1
fi
[[ $denial == *'permission denied for database "audipp_auth"'* ]]
compose stop db
[[ $(get_authenticated_api 503) == '{"error":"temporarily_unavailable"}' ]]
compose up -d --wait --wait-timeout 90 db
[[ $(get_authenticated_api 200) == "$owner" ]]

printf 'Checking Keycloak outage with an empty API key cache.\n'
compose stop keycloak
compose up -d --no-deps --force-recreate --wait --wait-timeout 60 api
api_port=$(port api 9443)
[[ $(get_authenticated_api 503) == '{"error":"temporarily_unavailable"}' ]]
compose up -d --wait --wait-timeout 150 keycloak
refresh_ports
token=$(get_token)
[[ $(get_authenticated_api 200) == "$owner" ]]

printf 'Checking a failed configuration and rollback to the previous configuration.\n'
if compose -f "$AUDIPP_SECRETS_DIR/broken.yaml" up -d --no-deps --wait --wait-timeout 30 api; then
    printf >&2 'The API unexpectedly started with no TLS store.\n'
    exit 1
fi
api_container=$(compose ps -aq api)
[[ $(docker_local inspect --format '{{.State.Status}}:{{.State.ExitCode}}' "$api_container") == exited:[1-9]* ]]
compose up -d --no-deps --wait --wait-timeout 60 api
refresh_ports
token=$(get_token)
[[ $(get_authenticated_api 200) == "$owner" ]]

printf 'Checking shutdown and restoring both dumps into a fresh project.\n'
compose stop api keycloak
for service in api keycloak; do
    container=$(compose ps -aq "$service")
    case $(docker_local inspect --format '{{.State.Status}}:{{.State.ExitCode}}' "$container") in
        exited:0|exited:143) ;;
        *) printf >&2 '%s did not stop normally.\n' "$service"; exit 1 ;;
    esac
done
for database in api auth; do
    compose run --rm -T --no-deps worker dump "$database" > "$AUDIPP_SECRETS_DIR/$database.dump"
done
compose stop db
project=$restore_project
compose up -d --wait --wait-timeout 90 db
if printf 'incomplete archive' | compose run --rm -T --no-deps worker restore api; then
    printf >&2 'The worker unexpectedly accepted an invalid archive.\n'
    exit 1
fi
for database in api auth; do
    compose run --rm -T --no-deps worker restore "$database" < "$AUDIPP_SECRETS_DIR/$database.dump"
done
compose up -d --wait --wait-timeout 210
refresh_ports
token=$(get_token)
[[ $(get_authenticated_api 200) == "$owner" ]]
project=$original_project
printf 'Compose smoke test passed.\n'
