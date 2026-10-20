#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/cleanup.sh [--all]

Default: delete namespace jafra-mart and keep the kind cluster and loaded images.
--all: also delete the jaframart cluster, prune dangling podman images, and remove
/tmp/jafra-mart-*.tar plus localhost/jafra-mart-build manifests.
EOF
}

ALL=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --all) ALL=true; shift ;;
    *) die "unknown argument: $1" ;;
  esac
done

prune_run_artifacts() {
  [[ -d "$ROOT/run-artifacts" ]] || return 0
  local keep_e2e="" keep_jfr="" d kind
  while IFS= read -r d; do
    [[ -f "$d/run-kind" ]] || continue
    kind="$(tr -d '[:space:]' < "$d/run-kind")"
    if [[ "$kind" == "e2e" ]]; then
      keep_e2e="$d"
    elif [[ "$kind" == "jfr-verify" ]]; then
      keep_jfr="$d"
    fi
  done < <(find "$ROOT/run-artifacts" -mindepth 1 -maxdepth 1 -type d | sort)
  while IFS= read -r d; do
    if [[ -n "$keep_e2e" && "$d" == "$keep_e2e" ]]; then
      continue
    fi
    if [[ -n "$keep_jfr" && "$d" == "$keep_jfr" ]]; then
      continue
    fi
    rm -rf "$d"
    log "removed $d"
  done < <(find "$ROOT/run-artifacts" -mindepth 1 -maxdepth 1 -type d | sort)
  log "kept run artifacts: ${keep_e2e:-none} ${keep_jfr:-none}"
}

require_cmd kubectl
if kubectl get namespace "$NAMESPACE" >/dev/null 2>&1; then
  kubectl delete namespace "$NAMESPACE" --wait=true
  log "deleted namespace $NAMESPACE"
else
  log "namespace $NAMESPACE is already absent"
fi

if [[ "$ALL" == true ]]; then
  require_cmd kind
  require_cmd podman
  if kind get clusters | grep -qx "$CLUSTER"; then
    kind delete cluster --name "$CLUSTER"
  fi
  rm -f /tmp/jafra-mart-*.tar
  while read -r manifest; do
    [[ -n "$manifest" ]] || continue
    podman manifest rm "$manifest" >/dev/null 2>&1 || true
  done < <(podman manifest ls --format '{{.Name}}' 2>/dev/null | grep 'localhost/jafra-mart-build/' || true)
  podman image prune -f >/dev/null
  log "removed cluster, archives, build manifests, and dangling images"
  prune_run_artifacts
fi
