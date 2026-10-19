#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/build-images.sh [--registry H] [--repo R] [--components "a b"] [--push]

Builds a multi-arch podman manifest list (linux/amd64,linux/arm64) per component.
Pushes to $REGISTRY/$REPO:v1-<component> only when --push is set.
EOF
}

PUSH=false
SELECTED="$COMPONENTS"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --push) PUSH=true; shift ;;
    --registry) REGISTRY="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --components) SELECTED="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd podman

for c in $SELECTED; do
  df="$(dockerfile_for "$c")"
  if [[ ! -f "$df" ]]; then
    log "skip $c (no Dockerfile yet)"
    continue
  fi
  local_manifest="localhost/jafra-mart-build/${c}:${VERSION}"
  podman manifest rm "$local_manifest" >/dev/null 2>&1 || true
  log "building $local_manifest from $df"
  podman build --platform "$PLATFORMS" --manifest "$local_manifest" -f "$df" .
  if [[ "$PUSH" == true ]]; then
    dest="$(image_for "$c")"
    log "pushing $dest"
    podman manifest push --all "$local_manifest" "docker://${dest}"
  fi
done
