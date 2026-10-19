#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/e2e-30min.sh [--time-scale 1.0|0.5|0.1] [--seed N]

The 30-minute run is finalized in branch 11. This stub refuses to pretend it passed.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --time-scale) shift 2 ;;
    --seed) shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

die "e2e-30min.sh is finalized in branch 11"
