#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/deploy.sh [--registry H] [--repo R] [--components "a b"]

Creates the namespace and postgres Secret when missing, applies the kustomize
manifests (optionally limited to namespace, config, postgres, and the named
components), then waits for rollouts.
EOF
}

SELECTED=""
FILTER=false
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --registry) REGISTRY="$2"; shift 2 ;;
    --repo) REPO="$2"; shift 2 ;;
    --components) SELECTED="$2"; FILTER=true; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd kubectl
require_cmd openssl

kubectl get namespace "$NAMESPACE" >/dev/null 2>&1 || kubectl create namespace "$NAMESPACE"
if ! kubectl -n "$NAMESPACE" get secret postgres-credentials >/dev/null 2>&1; then
  kubectl -n "$NAMESPACE" create secret generic postgres-credentials \
    --from-literal=username=jaframart \
    --from-literal=password="$(openssl rand -hex 16)"
fi

rendered="$(mktemp)"
trap 'rm -f "$rendered"' EXIT
kubectl kustomize manifests | sed "s#quay.io/bharathappali/jafra-mart#${REGISTRY}/${REPO}#g" > "$rendered"

if [[ "$FILTER" == true ]]; then
  filtered="$(mktemp)"
  awk -v allow=",${SELECTED// /,}," '
    function wanted(name,    i, n, parts) {
      if (name == "jafra-mart" || name == "jafra-mart-config" || name == "postgres") return 1
      n = split(allow, parts, ",")
      for (i = 1; i <= n; i++) if (parts[i] == name) return 1
      return 0
    }
    BEGIN { doc=""; name="" }
    /^---$/ {
      if (doc != "" && wanted(name)) printf "%s\n---\n", doc
      doc=""; name=""; next
    }
    { doc = doc $0 "\n" }
    /^  name: / { if (name == "") name=$2 }
    END { if (doc != "" && wanted(name)) printf "%s", doc }
  ' "$rendered" > "$filtered"
  kubectl apply -f "$filtered"
  rm -f "$filtered"
else
  kubectl apply -f "$rendered"
fi

for c in $DEPLOY_ORDER; do
  if [[ "$FILTER" == true && "$c" != "postgres" && " ${SELECTED} " != *" ${c} "* ]]; then
    continue
  fi
  if kubectl -n "$NAMESPACE" get deploy "$c" >/dev/null 2>&1; then
    kubectl -n "$NAMESPACE" rollout status "deploy/${c}" --timeout=300s
  fi
done
