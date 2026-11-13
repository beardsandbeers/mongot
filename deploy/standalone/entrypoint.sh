#!/usr/bin/env bash
set -euo pipefail

# Explicit application arguments opt into an operator-supplied configuration.
if (( $# > 0 )); then
  exec /opt/mongot/mongot-standalone "$@"
fi

state=/var/lib/mongot
config="${state}/config"
umask 077
mkdir -p "${state}/data"
if [[ ! -f "${config}/standalone.yaml" ]]; then
  if [[ -e "${config}" ]]; then
    echo 'mongot: config directory exists without standalone.yaml; restore or supply a complete configuration' >&2
    exit 1
  fi
  bootstrap="$(mktemp -d "${state}/.bootstrap.XXXXXXXX")"
  trap 'rm -rf -- "${bootstrap}"' EXIT
  openssl req -x509 -newkey rsa:3072 -sha256 -nodes -days 365 \
    -subj '/CN=mongot' -addext 'subjectAltName=DNS:mongot,DNS:localhost,IP:127.0.0.1' \
    -addext 'basicConstraints=critical,CA:FALSE' \
    -addext 'keyUsage=critical,digitalSignature,keyEncipherment' \
    -addext 'extendedKeyUsage=serverAuth' \
    -keyout "${bootstrap}/server.key" -out "${bootstrap}/server.crt" >/dev/null 2>&1
  openssl rand -hex 32 > "${bootstrap}/admin.token"
  cat > "${bootstrap}/standalone.yaml" <<'CONFIG'
mode: standalone
storage:
  dataPath: /var/lib/mongot/data
  sourceStore:
    engine: sqlite
    synchronous: full
server:
  grpc:
    address: 0.0.0.0:27029
    tls:
      certificate: server.crt
      privateKey: server.key
    principals:
      - name: admin
        tokenFile: admin.token
        roles: [catalog, write, query]
        collections: ['*']
CONFIG
  # Publish the complete identity atomically; never bake credentials into an image or rotate on restart.
  mv -T -- "${bootstrap}" "${config}"
  trap - EXIT
  echo 'mongot: initialized persistent TLS identity and admin token under /var/lib/mongot/config' >&2
fi
exec /opt/mongot/mongot-standalone --config "${config}/standalone.yaml"
