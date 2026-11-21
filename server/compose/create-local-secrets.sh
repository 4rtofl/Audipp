#!/usr/bin/env bash
set -euo pipefail
umask 077

# Compose binds individual files while retaining host ownership. The parent directory stays private.
secret_dir=$(mktemp -d /tmp/audipp-secrets.XXXXXXXX)
trap 'find "$secret_dir" -depth -delete' EXIT
for name in db-admin-password api-db-password auth-db-password auth-bootstrap-password api-tls-password; do
    openssl rand -hex 32 > "$secret_dir/$name"
done

openssl genrsa -out "$secret_dir/ca.key" 2048
openssl req -new -x509 -key "$secret_dir/ca.key" -days 7 -sha256 \
    -subj '/CN=Audipp local test CA' -addext 'basicConstraints=critical,CA:TRUE' \
    -addext 'keyUsage=critical,keyCertSign,cRLSign' -out "$secret_dir/ca.crt"
for service in auth api; do
    fqdn="$service.audipp.test"
    openssl genrsa -out "$secret_dir/$service.key" 2048
    openssl req -new -key "$secret_dir/$service.key" -subj "/CN=$fqdn" \
        -addext "subjectAltName=DNS:$fqdn" -out "$secret_dir/$service.csr"
    openssl x509 -req -copy_extensions copy -days 7 -sha256 \
        -in "$secret_dir/$service.csr" -CA "$secret_dir/ca.crt" -CAkey "$secret_dir/ca.key" -set_serial "0x$(openssl rand -hex 16)" \
        -out "$secret_dir/$service.crt"
done

openssl pkcs12 -export -name audipp-api -inkey "$secret_dir/api.key" -in "$secret_dir/api.crt" \
    -certfile "$secret_dir/ca.crt" -out "$secret_dir/api.p12" \
    -passout "file:$secret_dir/api-tls-password"
keytool -importcert -noprompt -alias audipp-local -file "$secret_dir/ca.crt" \
    -storetype PKCS12 -keystore "$secret_dir/trust.p12" -storepass changeit >&2
rm -- "$secret_dir/"{ca.key,api.key,api.crt,api.csr,auth.csr}
chmod 444 "$secret_dir/"*
trap - EXIT
printf '%s\n' "$secret_dir"
