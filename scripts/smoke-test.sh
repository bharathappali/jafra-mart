#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/smoke-test.sh

Checks every Deployment is Available, /api/info, storefront logins, one checkout,
loadgen health, idle scenarios, and restart count 0. Components that are not
deployed yet are skipped.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd kubectl
require_cmd curl
require_cmd jq

PIDS=()
cleanup() {
  local pid
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

not_ready="$(kubectl -n "$NAMESPACE" get deploy -o json | jq -r '.items[] | select(.status.availableReplicas != 1) | .metadata.name')"
if [[ -n "$not_ready" ]]; then
  die "deployments not available: $not_ready"
fi

for c in $COMPONENTS; do
  if ! kubectl -n "$NAMESPACE" get deploy "$c" >/dev/null 2>&1; then
    continue
  fi
  port="$(port_for "$c")"
  kubectl -n "$NAMESPACE" port-forward "svc/${c}" "${port}:8080" >/dev/null &
  PIDS+=("$!")
  sleep 0.4
  code="$(curl -sS -o /tmp/jafra-mart-info.json -w '%{http_code}' --max-time 5 "http://127.0.0.1:${port}/api/info" || true)"
  [[ "$code" == "200" ]] || die "$c /api/info returned $code"
  # storefront returns 302 (empty) for /internal/scenarios; only parse real JSON arrays
  if curl -sf --max-time 5 "http://127.0.0.1:${port}/internal/scenarios" -o /tmp/jafra-mart-scenarios.json \
      && [[ -s /tmp/jafra-mart-scenarios.json ]] \
      && jq -e 'type == "array"' /tmp/jafra-mart-scenarios.json >/dev/null 2>&1; then
    bad="$(jq -r '[.[] | select(.state != "IDLE")] | length' /tmp/jafra-mart-scenarios.json)"
    [[ "$bad" == "0" ]] || die "$c has a scenario that is not IDLE"
  fi
done

if kubectl -n "$NAMESPACE" get deploy storefront >/dev/null 2>&1; then
  jar="$(mktemp)"
  code="$(curl -sS -c "$jar" -o /tmp/jafra-mart-login.json -w '%{http_code}' --max-time 10 \
    -H 'content-type: application/json' -d '{"username":"user","password":"user"}' \
    http://127.0.0.1:30080/api/auth/login || true)"
  [[ "$code" == "200" ]] || die "user login returned $code"
  code="$(curl -sS -c "$jar" -o /dev/null -w '%{http_code}' --max-time 10 \
    -H 'content-type: application/json' -d '{"username":"admin","password":"admin"}' \
    http://127.0.0.1:30080/api/auth/login || true)"
  [[ "$code" == "200" ]] || die "admin login returned $code"
  if kubectl -n "$NAMESPACE" get deploy orders >/dev/null 2>&1; then
    ujar="$(mktemp)"
    curl -sf -c "$ujar" -H 'content-type: application/json' -d '{"username":"user","password":"user"}' \
      http://127.0.0.1:30080/api/auth/login >/dev/null
    curl -sf -b "$ujar" -c "$ujar" -H 'content-type: application/json' \
      -d '{"sku":"SKU-00042","quantity":2}' http://127.0.0.1:30080/api/cart/items >/dev/null
    code="$(curl -sS -b "$ujar" -o /tmp/jafra-mart-order.json -w '%{http_code}' --max-time 20 \
      -H 'content-type: application/json' -d '{"cardToken":"tok_visa"}' \
      http://127.0.0.1:30080/api/checkout || true)"
    [[ "$code" == "201" ]] || die "checkout returned $code"
    jq -e '.status == "PLACED"' /tmp/jafra-mart-order.json >/dev/null || die "checkout status was not PLACED"
  fi
fi

if kubectl -n "$NAMESPACE" get deploy loadgen >/dev/null 2>&1; then
  jq -e '.customersLoggedIn == 200' < <(curl -sf --max-time 10 http://127.0.0.1:30081/api/health) >/dev/null \
    || die "loadgen health did not report 200 customers"
fi

restarts="$(kubectl -n "$NAMESPACE" get pods -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[0].restartCount}{"\n"}{end}')"
if grep -v ' 0$' <<<"$restarts" | grep -q .; then
  die "restarts: $restarts"
fi
log "smoke ok"
