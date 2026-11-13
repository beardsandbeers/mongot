#!/usr/bin/env bash
# Build all native clients, then exercise every RPC over TLS against isolated mongot state.
set -euo pipefail
CLIENTS_DIR="$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)"
MONGOT_PYTHON="${MONGOT_PYTHON:-${CLIENTS_DIR}/python/.venv/bin/python}"
MONGOT_MVN="${MONGOT_MVN:-mvn}"
python3 "${CLIENTS_DIR}/sync_proto.py" --check
if [[ ! -x "${MONGOT_PYTHON}" ]]; then
  python3 -m venv "${CLIENTS_DIR}/python/.venv"
fi
"${MONGOT_PYTHON}" -m pip install -e "${CLIENTS_DIR}/python[codegen]"
"${MONGOT_PYTHON}" "${CLIENTS_DIR}/python/generate.py"
"${MONGOT_MVN}" -B -ntp -f "${CLIENTS_DIR}/java/pom.xml" package
(
  cd "${CLIENTS_DIR}/node"
  if [[ -f package-lock.json ]]; then npm ci --no-audit --no-fund; else npm install --no-audit --no-fund; fi
  npm run build
)
cargo build --manifest-path "${CLIENTS_DIR}/rust/Cargo.toml"
cargo test --manifest-path "${CLIENTS_DIR}/rust/Cargo.toml" --lib --locked
"${MONGOT_PYTHON}" "${CLIENTS_DIR}/tests/e2e.py" "$@"
