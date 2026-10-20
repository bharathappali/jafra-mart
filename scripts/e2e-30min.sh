#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/e2e-30min.sh [--time-scale 1.0|0.5|0.1] [--seed N]

Brings the cluster up, runs default-30m, and samples every 5s (plus 60s after
the run reaches COMPLETED) into run-artifacts/<UTC>/samples.jsonl.
Validates with scripts/ValidateRun.java. Does not delete the namespace.

When E2E_KUSTOMIZE is set, deploy.sh applies that kustomize directory
(used by jfr-verify.sh so EXTRA_JAVA_OPTS survives deploy).
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
printf '%s\n' "${JAFRA_RUN_KIND:-e2e}" > "$ARTIFACTS/run-kind"
log "artifacts: $ARTIFACTS"

"$ROOT/scripts/kind-up.sh"
"$ROOT/scripts/kind-load.sh"
if [[ -n "${E2E_KUSTOMIZE:-}" ]]; then
  log "deploying kustomize overlay $E2E_KUSTOMIZE"
  "$ROOT/scripts/deploy.sh" --kustomize "$E2E_KUSTOMIZE"
else
  "$ROOT/scripts/deploy.sh"
fi
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
run_id=""
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
printf '%s\n' "$run_id" > "$ARTIFACTS/run-id"
log "started $run_id"

samples="$ARTIFACTS/samples.jsonl"
: > "$samples"
extra_secs="$(awk -v ts="$TIME_SCALE" 'BEGIN { printf "%d", 1800 * ts + 300 }')"
deadline=$(( $(date +%s) + extra_secs ))
jvm_pods=(catalog-search pricing inventory cart payments reports-worker recommendations orders storefront)
work="$ARTIFACTS/.sample-work"
completed_at=0
sample_n=0

read_cgroup() {
  local c="$1" dest="$2"
  kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- sh -c 'echo ===CPU===; cat /sys/fs/cgroup/cpu.stat; echo ===CUR===; cat /sys/fs/cgroup/memory.current; echo ===STAT===; cat /sys/fs/cgroup/memory.stat; echo ===MAX===; cat /sys/fs/cgroup/memory.max' \
    > "$dest" 2>/dev/null || true
}

while true; do
  loop_start=$(date +%s)
  rm -rf "$work"
  mkdir -p "$work"
  curl -sf --max-time 5 http://127.0.0.1:30081/api/runs/current > "$work/run.json" || echo '{}' > "$work/run.json" &
  curl -sf --max-time 5 http://127.0.0.1:30081/api/scenarios > "$work/scenarios.json" || echo '[]' > "$work/scenarios.json" &
  curl -sf --max-time 5 http://127.0.0.1:30081/api/load > "$work/load.json" || echo '{}' > "$work/load.json" &
  curl -sf --max-time 5 'http://127.0.0.1:30081/api/load/latency?step=checkout&window=60' > "$work/latency.json" || echo '{}' > "$work/latency.json" &
  ready_code_file="$work/ready.code"
  (curl -s -o /dev/null -w '%{http_code}' --max-time 3 http://127.0.0.1:30080/actuator/health/readiness || echo 000) > "$ready_code_file" &
  pids=()
  for c in "${jvm_pods[@]}"; do
    read_cgroup "$c" "$work/$c.raw" &
    pids+=("$!")
  done
  wait || true
  ready_code="$(cat "$ready_code_file" 2>/dev/null || echo 000)"
  jq -e . "$work/scenarios.json" >/dev/null 2>&1 || echo '[]' > "$work/scenarios.json"
  jq -e . "$work/load.json" >/dev/null 2>&1 || echo '{}' > "$work/load.json"
  jq -e . "$work/latency.json" >/dev/null 2>&1 || echo '{}' > "$work/latency.json"
  jq -e . "$work/run.json" >/dev/null 2>&1 || echo '{}' > "$work/run.json"

  cgroups='{}'
  for c in "${jvm_pods[@]}"; do
    [[ -s "$work/$c.raw" ]] || continue
    parsed="$(awk '
      $0=="===CPU===" {s="cpu"; next}
      $0=="===CUR===" {s="cur"; next}
      $0=="===STAT===" {s="stat"; next}
      $0=="===MAX===" {s="max"; next}
      s=="cpu" && $1=="nr_throttled" {nr=$2}
      s=="cpu" && $1=="throttled_usec" {tu=$2}
      s=="cur" && $1 ~ /^[0-9]+$/ {mc=$1}
      s=="stat" && $1=="inactive_file" {inf=$2}
      s=="stat" && $1=="file" {fi=$2}
      s=="stat" && $1=="anon" {an=$2}
      s=="max" && $1 ~ /^[0-9]+$/ {mm=$1}
      END {
        if (mm == "") exit 1
        printf "%s %s %s %s %s %s %s\n", nr+0, tu+0, mc+0, mm, inf+0, fi+0, an+0
      }
    ' "$work/$c.raw" || true)"
    [[ -n "$parsed" ]] || continue
    read -r nr tu mc mm inf fi an <<<"$parsed"
    cgroups="$(jq -c --arg c "$c" \
      --argjson nr "$nr" --argjson tu "$tu" --argjson mc "$mc" --argjson mm "$mm" \
      --argjson inf "$inf" --argjson fi "$fi" --argjson an "$an" \
      '.[$c]={nrThrottled:$nr,throttledUsec:$tu,memoryCurrent:$mc,memoryMax:$mm,inactiveFile:$inf,file:$fi,anon:$an}' \
      <<<"$cgroups")"
  done
  printf '%s\n' "$cgroups" > "$work/cgroups.json"

  state="$(jq -r '.state // "UNKNOWN"' "$work/run.json")"
  elapsed="$(jq -r '.elapsedSeconds // 0' "$work/run.json")"
  ready_json=false
  [[ "$ready_code" == "200" ]] && ready_json=true
  jq -nc \
    --arg ts "$(date -u +%Y-%m-%dT%H:%M:%SZ)" \
    --argjson elapsed "$elapsed" \
    --arg state "$state" \
    --arg runId "$run_id" \
    --argjson ready "$ready_json" \
    --slurpfile scenarios "$work/scenarios.json" \
    --slurpfile load "$work/load.json" \
    --slurpfile latency "$work/latency.json" \
    --slurpfile cgroups "$work/cgroups.json" \
    '{ts:$ts,elapsedSeconds:$elapsed,state:$state,runId:$runId,storefrontReady:$ready,scenarios:$scenarios[0],load:$load[0],latency:$latency[0],cgroups:$cgroups[0]}' \
    >> "$samples"
  sample_n=$((sample_n + 1))
  if (( sample_n % 12 == 0 )); then
    log "t=${elapsed}s state=$state samples=$sample_n"
  fi

  if [[ "$state" == "COMPLETED" || "$state" == "STOPPED" ]]; then
    if [[ "$completed_at" -eq 0 ]]; then
      completed_at=$(date +%s)
      log "run $state; sampling 60s more"
    fi
  fi
  if [[ "$completed_at" -ne 0 ]] && (( $(date +%s) >= completed_at + 60 )); then
    break
  fi
  if (( $(date +%s) > deadline )); then
    log "deadline reached before 60s post-run window"
    break
  fi
  now=$(date +%s)
  sleep_for=$((5 - (now - loop_start)))
  if (( sleep_for > 0 )); then
    sleep "$sleep_for"
  fi
done

rm -rf "$work"
log "saving ground truth and cluster snapshot"
curl -sf "http://127.0.0.1:30081/api/ground-truth?runId=${run_id}" -o "$ARTIFACTS/ground-truth.jsonl" \
  || : > "$ARTIFACTS/ground-truth.jsonl"
kubectl -n "$NAMESPACE" get pods -o wide > "$ARTIFACTS/pods.txt"
kubectl -n "$NAMESPACE" get events --sort-by=.lastTimestamp > "$ARTIFACTS/events.txt" || true

java "$ROOT/scripts/ValidateRun.java" "$ARTIFACTS/ground-truth.jsonl" "$ARTIFACTS/samples.jsonl" --time-scale "$TIME_SCALE" \
  | tee "$ARTIFACTS/validate.txt"
log "e2e-30min ok (time-scale=$TIME_SCALE runId=$run_id)"
