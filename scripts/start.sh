#!/usr/bin/env bash
set -euo pipefail
cd "$(dirname "$0")/.."
if [[ ! -f backend/target/akhil-api-2.0.0.jar ]]; then
  printf '%s\n' 'Run bash scripts/build.sh first.' >&2
  exit 1
fi
exec java -jar backend/target/akhil-api-2.0.0.jar
