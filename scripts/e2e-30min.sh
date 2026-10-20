#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/e2e-30min.sh [--time-scale 1.0|0.5|0.1] [--seed N]

Runs the default-30m script through loadgen, samples metrics, validates ground truth.
At --time-scale 0.1 only structure/timing/recovery/stability/load-health are asserted (§20.3).
Does not clean up the namespace.
EOF
}

TIME_SCALE="1.0"
SEED="20261019"
while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    --time-scale) TIME_SCALE="$2"; shift 2 ;;
    --seed) SEED="$2"; shift 2 ;;
    *) die "unknown argument: $1" ;;
  esac
done

case "$TIME_SCALE" in
  1.0|0.5|0.1) ;;
  *) die "time-scale must be 1.0, 0.5, or 0.1" ;;
esac

require_cmd kubectl
require_cmd curl
require_cmd jq
require_cmd java

mkdir -p "$ARTIFACTS"
log "artifacts: $ARTIFACTS"

"$ROOT/scripts/kind-up.sh"
"$ROOT/scripts/kind-load.sh"
"$ROOT/scripts/deploy.sh"
"$ROOT/scripts/smoke-test.sh"

log "waiting for loadgen health (200 customers)"
for _ in $(seq 1 90); do
  if curl -sf http://127.0.0.1:30081/api/health | jq -e '.customersLoggedIn == 200 and .baseline == true' >/dev/null 2>&1; then
    break
  fi
  sleep 2
done
curl -sf http://127.0.0.1:30081/api/health | jq -e '.customersLoggedIn == 200' >/dev/null \
  || die "loadgen not healthy before baseline wait"

log "waiting 60s of baseline load"
sleep 60

run_body=""
for _ in 1 2 3 4 5; do
  run_body="$(curl -sS -X POST http://127.0.0.1:30081/api/runs \
    -H 'content-type: application/json' \
    -d "{\"script\":\"default-30m\",\"seed\":${SEED},\"timeScale\":${TIME_SCALE}}" || true)"
  run_id="$(jq -r '.runId // empty' <<<"$run_body" 2>/dev/null || true)"
  if [[ -n "$run_id" ]]; then
    break
  fi
  sleep 3
done
[[ -n "$run_id" ]] || die "failed to start run: $run_body"
log "started $run_id"

samples="$ARTIFACTS/samples.jsonl"
: > "$samples"
extra_secs="$(awk -v ts="$TIME_SCALE" 'BEGIN { printf "%d", 1800 * ts + 120 }')"
deadline=$(( $(date +%s) + extra_secs ))

jvm_pods=(catalog-search pricing inventory cart payments reports-worker recommendations orders storefront)

while true; do
  state="$(curl -sf http://127.0.0.1:30081/api/runs/current | jq -r '.state' || echo UNKNOWN)"
  sample="$(jq -nc --arg ts "$(date -u +%Y-%m-%dT%H:%M:%S.%3NZ)" '{ts:$ts}')"
  scenarios="$(curl -sf http://127.0.0.1:30081/api/scenarios || echo '[]')"
  loadsnap="$(curl -sf http://127.0.0.1:30081/api/load || echo '{}')"
  latency="$(curl -sf 'http://127.0.0.1:30081/api/load/latency?step=checkout&window=60' || echo '{}')"
  cgroups='{}'
  for c in "${jvm_pods[@]}"; do
    if kubectl -n "$NAMESPACE" get deploy "$c" >/dev/null 2>&1; then
      cpu="$(kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- cat /sys/fs/cgroup/cpu.stat 2>/dev/null || true)"
      memc="$(kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- cat /sys/fs/cgroup/memory.current 2>/dev/null || echo 0)"
      mems="$(kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- cat /sys/fs/cgroup/memory.stat 2>/dev/null || true)"
      memmax="$(kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- cat /sys/fs/cgroup/memory.max 2>/dev/null || echo max)"
      inactive="$(awk '/^inactive_file /{print $2}' <<<"$mems")"
      file="$(awk '/^file /{print $2}' <<<"$mems")"
      anon="$(awk '/^anon /{print $2}' <<<"$mems")"
      nr="$(awk '/^nr_throttled /{print $2}' <<<"$cpu")"
      cgroups="$(jq -c --arg c "$c" --argjson memc "${memc:-0}" --arg memmax "$memmax" \
        --argjson inactive "${inactive:-0}" --argjson file "${file:-0}" --argjson anon "${anon:-0}" \
        --argjson nr "${nr:-0}" \
        '.[$c]={memoryCurrent:($memc|tonumber),memoryMax:$memmax,inactiveFile:($inactive|tonumber),file:($file|tonumber),anon:($anon|tonumber),nrThrottled:($nr|tonumber)}' \
        <<<"$cgroups")"
    fi
  done
  jq -nc --argjson base "$sample" --argjson scenarios "$scenarios" --argjson load "$loadsnap" \
    --argjson latency "$latency" --argjson cgroups "$cgroups" --arg state "$state" \
    '$base + {state:$state,scenarios:$scenarios,load:$load,latency:$latency,cgroups:$cgroups}' >> "$samples"

  if [[ "$state" == "COMPLETED" || "$state" == "STOPPED" ]]; then
    break
  fi
  if (( $(date +%s) > deadline )); then
    die "run did not complete before deadline"
  fi
  sleep 5
done

log "cooling 60s after completion"
sleep 60

curl -sf "http://127.0.0.1:30081/api/ground-truth?runId=${run_id}" -o "$ARTIFACTS/ground-truth.jsonl"
kubectl -n "$NAMESPACE" get pods -o wide > "$ARTIFACTS/pods.txt"
kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp > "$ARTIFACTS/events.txt" || true

java "$ROOT/scripts/ValidateRun.java" "$ARTIFACTS/ground-truth.jsonl" "$ARTIFACTS/samples.jsonl" --time-scale "$TIME_SCALE"
log "e2e-30min ok (time-scale=$TIME_SCALE runId=$run_id)"
