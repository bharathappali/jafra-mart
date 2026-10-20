#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/kind-load.sh [--components "a b"]

Builds each component for linux/$NATIVE_ARCH, saves a docker-archive, and
loads it into the kind cluster. Always pre-loads postgres:18.6-alpine3.24.
Missing Dockerfiles are skipped.
EOF
}

SELECTED="$COMPONENTS"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --components) SELECTED="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd podman
require_cmd kind

load_archive() {
  local tar="$1"
  local image="$2"
  podman save --format docker-archive -o "$tar" "$image"
  kind load image-archive "$tar" --name "$CLUSTER"
  rm -f "$tar"
}

log "pre-loading postgres:18.6-alpine3.24"
podman pull --platform "linux/${NATIVE_ARCH}" docker.io/library/postgres:18.6-alpine3.24
load_archive "/tmp/jafra-mart-postgres.tar" "docker.io/library/postgres:18.6-alpine3.24"

for c in $SELECTED; do
  df="$(dockerfile_for "$c")"
  if [[ ! -f "$df" ]]; then
    log "skip $c (no Dockerfile yet)"
    continue
  fi
  image="$(image_for "$c")"
  log "building $image for linux/${NATIVE_ARCH}"
  podman build --platform "linux/${NATIVE_ARCH}" -t "$image" -f "$df" .
  load_archive "/tmp/jafra-mart-${c}.tar" "$image"
  # Same tag + imagePullPolicy IfNotPresent does not refresh running pods; restart when present.
  if command -v kubectl >/dev/null 2>&1 && kubectl -n "$NAMESPACE" get deploy "$c" >/dev/null 2>&1; then
    kubectl -n "$NAMESPACE" rollout restart "deploy/${c}"
  fi
done
