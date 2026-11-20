#!/usr/bin/env bash
set -euo pipefail

ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
TOKEN_FILE="${QB2_TOKEN_FILE:-$HOME/.config/qb2/nerve-token}"

if [[ -z "${QB2_NERVE_TOKEN:-}" ]]; then
  if [[ ! -f "$TOKEN_FILE" ]]; then
    mkdir -p "$(dirname "$TOKEN_FILE")"
    umask 077
    openssl rand -hex 32 > "$TOKEN_FILE"
    chmod 600 "$TOKEN_FILE"
    echo "Generated new NERVE token at $TOKEN_FILE (not printed for security)."
  fi
  QB2_NERVE_TOKEN="$(tr -d '[:space:]' < "$TOKEN_FILE")"
  export QB2_NERVE_TOKEN
fi

export QB2_NERVE_REPO="${QB2_NERVE_REPO:-/mnt/lfs/lfs/repos/nerve}"
exec bun "$ROOT/bridge/server.ts"
