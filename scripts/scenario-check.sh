#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/scenario-check.sh <component> <scenarioId> [miniload args…]

Starts the scenario for 10s ramp / 30s hold / 10s recover, runs the §19.3
MiniLoad shape (or the extra args, which replace the default shape), and
checks the hold and recovery thresholds. Fails if any pod restarted.
EOF
}

if [[ "${1:-}" == "--help" || "${1:-}" == "-h" || $# -lt 2 ]]; then
  usage
  [[ "${1:-}" == "--help" || "${1:-}" == "-h" ]] && exit 0
  exit 1
fi

require_cmd kubectl
require_cmd curl
require_cmd jq
require_cmd java

COMP="$1"
ID="$2"
shift 2
EXTRA=("$@")

PIDS=()
cleanup() {
  local pid
  for pid in "${PIDS[@]:-}"; do
    kill "$pid" >/dev/null 2>&1 || true
  done
}
trap cleanup EXIT

port="$(port_for "$COMP")"
kubectl -n "$NAMESPACE" port-forward "svc/${COMP}" "${port}:8080" >/dev/null &
PIDS+=("$!")
base="http://127.0.0.1:${port}"
for _ in $(seq 1 30); do
  if curl -sf -o /dev/null --max-time 1 "${base}/internal/scenarios"; then
    break
  fi
  sleep 0.3
done

metric() { jq -r ".metrics.${1}" <<<"$2"; }

restarts() {
  kubectl -n "$NAMESPACE" get pods -o jsonpath='{range .items[*]}{.metadata.name} {.status.containerStatuses[0].restartCount}{"\n"}{end}'
}

before="$(restarts)"
cpu_stat() {
  kubectl -n "$NAMESPACE" exec "deploy/${COMP}" -c "$COMP" -- cat /sys/fs/cgroup/cpu.stat
}
cpu_field() {
  awk -v k="$2" '$1==k {print $2}' <<<"$1"
}

status_json="$(curl -sf "${base}/internal/scenarios/${ID}")"
before_code="$(metric codeCacheUsedPct "$status_json" 2>/dev/null || true)"

if [[ "$ID" == "S07" ]]; then
  curl -sf -X POST "${base}/api/carts/customer001/items" -H 'content-type: application/json' \
    -d '{"sku":"SKU-00001","quantity":1}' >/dev/null
fi
if [[ "$ID" == "S11" ]]; then
  for n in $(seq 1 20); do
    curl -sf -X POST "${base}/api/invoices" -H 'content-type: application/json' \
      -d "{\"orderNumber\":\"JM-20261019-$(printf '%06d' "$n")\",\"customerId\":\"customer001\",\"customerName\":\"Asha Rao\",\"status\":\"PLACED\",\"subtotalCents\":1000,\"discountCents\":0,\"totalCents\":1000,\"items\":[{\"sku\":\"SKU-00001\",\"productName\":\"Item\",\"quantity\":1,\"unitPriceCents\":1000}]}" >/dev/null
  done
fi

curl -sf -X POST "${base}/internal/scenarios/${ID}/start" -H 'content-type: application/json' \
  -d '{"rampSeconds":10,"holdSeconds":30,"recoverSeconds":10,"intensity":1.0}' >/dev/null

if [[ ${#EXTRA[@]} -gt 0 ]]; then
  java "$ROOT/scripts/MiniLoad.java" "${EXTRA[@]}" > /tmp/jafra-mart-miniload.json &
  PIDS+=("$!")
else
  case "$ID" in
    S01) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/search?fuzzy=true&limit=20&q=frostd%20glass%20lmp" --method GET --rps 60 --seconds 55 --concurrency 8 ;;
    S10) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/products/SKU-{seq5}/thumbnail?size=256" --method GET --rps 20 --seconds 55 ;;
    S09) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/quote" --method POST --body '{"customerId":"customer007","couponCode":"WELCOME10","items":[{"sku":"SKU-00042","quantity":2}]}' --rps 10 --seconds 55 ;;
    S02) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/inventory/SKU-00042" --method GET --rps 200 --seconds 55 ;;
    S07) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/carts/customer001/coupon" --method POST --body '{"code":"{rand8}"}' --rps 200 --seconds 55 --concurrency 16 ;;
    S03) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/payments/authorize" --method POST --body '{"orderNumber":"JM-20261019-{seq}","customerId":"user","amountCents":23398,"currency":"USD","cardToken":"tok_visa"}' --rps 3 --seconds 55 ;;
    S12) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/payments/authorize" --method POST --body '{"orderNumber":"JM-20261019-{seq}","customerId":"user","amountCents":23398,"currency":"USD","cardToken":"tok_visa"}' --rps 5 --seconds 55 ;;
    S06) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/reports/exports/orders?rows=50000" --method GET --rps 2 --seconds 55 --concurrency 2 ;;
    S11) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/invoices/reprint" --method POST --body '{"count":1}' --rps 10 --seconds 55 ;;
    S13) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/reports/daily" --method GET --rps 5 --seconds 55 ;;
    S05) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/recommendations?customerId=customer001&productId=SKU-{seq5}&sessionId={rand8}" --method GET --rps 30 --seconds 55 ;;
    S04) java "$ROOT/scripts/MiniLoad.java" --url "${base}/api/orders" --method POST --body '{"customerId":"customer001","customerName":"Asha Rao","cardToken":"tok_visa","items":[{"sku":"SKU-00001","quantity":1}]}' --burst 25 --every 10 --seconds 55 ;;
    S08) ;;
    *) die "no MiniLoad shape for $ID" ;;
  esac > /tmp/jafra-mart-miniload.json &
  if [[ "$ID" != "S08" ]]; then
    PIDS+=("$!")
  fi
fi

seen_r=0 seen_h=0 seen_v=0 seen_i=0
prev=""
hold_status=""
hold_cpu_start=""
hold_cpu_end=""
hold_exc_start=""
deadline=$((SECONDS + 180))
while [[ $SECONDS -lt $deadline ]]; do
  status_json="$(curl -sf --max-time 3 "${base}/internal/scenarios/${ID}" || true)"
  if [[ -z "$status_json" ]]; then
    sleep 2
    continue
  fi
  state="$(jq -r '.state' <<<"$status_json")"
  case "$state" in
    RAMPING) seen_r=1 ;;
    HOLDING)
      seen_h=1
      if [[ "$prev" != "HOLDING" ]]; then
        hold_cpu_start="$(cpu_stat || true)"
        hold_exc_start="$(metric exceptionsThrown "$status_json")"
        young_start="$(metric youngGcCount "$status_json")"
        fsync_start="$(metric fsyncs "$status_json")"
      fi
      hold_status="$status_json"
      hold_cpu_end="$(cpu_stat || true)"
      ;;
    RECOVERING) seen_v=1 ;;
    IDLE)
      if [[ "$seen_v" == 1 ]]; then
        seen_i=1
        break
      fi
      ;;
  esac
  prev="$state"
  sleep 2
done

[[ "$seen_r" == 1 && "$seen_h" == 1 && "$seen_v" == 1 && "$seen_i" == 1 ]] || die "$ID missed a phase r=$seen_r h=$seen_h v=$seen_v i=$seen_i"

hold_check() {
  case "$ID" in
    S01)
      d1="$(cpu_field "$hold_cpu_start" nr_throttled)"
      d2="$(cpu_field "$hold_cpu_end" nr_throttled)"
      [[ $((d2 - d1)) -ge 30 ]] || die "S01 nr_throttled delta $((d2 - d1)) < 30"
      ;;
    S10) [[ "$(metric offHeapBytes "$hold_status")" -ge $((60 * 1024 * 1024)) ]] || die "S10 offHeapBytes" ;;
    S08) [[ "$(metric hiddenClassesLive "$hold_status")" -ge 1500 ]] || die "S08 hiddenClassesLive" ;;
    S09)
      pipes="$(metric pipesCreated "$hold_status")"
      code="$(metric codeCacheUsedPct "$hold_status")"
      [[ "$pipes" -ge 300 ]] || die "S09 pipesCreated"
      awk -v a="$code" -v b="$before_code" 'BEGIN { exit !(a >= b + 15) }' || die "S09 code cache"
      ;;
    S02) awk -v v="$(metric hotSkuLockWaitMsP95 "$hold_status")" 'BEGIN { exit !(v >= 20) }' || die "S02 lock wait" ;;
    S07)
      end="$(metric exceptionsThrown "$hold_status")"
      [[ $((end - hold_exc_start)) -ge 20000 ]] || die "S07 exceptions"
      ;;
    S03)
      probe="$(curl -sf -X POST "${base}/api/payments/authorize" -H 'content-type: application/json' \
        -d '{"orderNumber":"JM-PROBE-HOLD","customerId":"user","amountCents":1000,"currency":"USD","cardToken":"tok_visa"}')"
      awk -v v="$(jq -r '.latencyMs' <<<"$probe")" 'BEGIN { exit !(v >= 800) }' || die "S03 latency"
      ;;
    S12)
      [[ "$(metric pinnedScoringCalls "$hold_status")" -ge 50 ]] || die "S12 pinned"
      awk -v v="$(metric authorizeMsP95 "$hold_status")" 'BEGIN { exit !(v >= 200) }' || die "S12 p95"
      ;;
    S06)
      [[ "$(metric exportsCompleted "$hold_status")" -ge 20 ]] || die "S06 exports"
      nowy="$(metric youngGcCount "$hold_status")"
      [[ $((nowy - young_start)) -ge 20 ]] || die "S06 young gc"
      ;;
    S11)
      nowf="$(metric fsyncs "$hold_status")"
      [[ $((nowf - fsync_start)) -ge 3000 ]] || die "S11 fsyncs"
      ;;
    S13) [[ "$(metric heartbeatStallsOver100ms "$hold_status")" -ge 3 ]] || die "S13 stalls" ;;
    S05) [[ "$(metric cacheBytes "$hold_status")" -ge $((50 * 1024 * 1024)) ]] || die "S05 cache" ;;
    S04)
      [[ "$(metric leakedPools "$hold_status")" -ge 4 ]] || die "S04 pools"
      [[ "$(metric threadCount "$hold_status")" -ge 90 ]] || die "S04 threads"
      ;;
  esac
}
hold_check

idle_status="$(curl -sf "${base}/internal/scenarios/${ID}")"
sleep 15
rec_status="$(curl -sf "${base}/internal/scenarios/${ID}")"

recovery_check() {
  case "$ID" in
    S01) [[ "$(metric fuzzyExpansions "$rec_status")" == "1" ]] || die "S01 recovery" ;;
    S10)
      [[ "$(metric offHeapBytes "$rec_status")" -le $((20 * 1024 * 1024)) ]] || die "S10 offheap recovery"
      [[ "$(metric diskBytes "$rec_status")" == "0" ]] || die "S10 disk recovery"
      ;;
    S08)
      [[ "$(metric hiddenClassesLive "$rec_status")" == "0" ]] || die "S08 classes recovery"
      awk -v a="$(metric metaspaceUsedMiB "$rec_status")" -v b="$(metric metaspaceUsedMiB "$hold_status")" 'BEGIN { exit !(a <= b - 5) }' || die "S08 metaspace recovery"
      ;;
    S09) [[ "$(metric livePipes "$rec_status")" == "0" ]] || die "S09 pipes recovery" ;;
    S02) awk -v v="$(metric hotSkuLockWaitMsP95 "$rec_status")" 'BEGIN { exit !(v <= 5) }' || die "S02 recovery" ;;
    S07)
      a="$(metric exceptionsThrown "$idle_status")"
      b="$(metric exceptionsThrown "$rec_status")"
      awk -v d="$((b - a))" 'BEGIN { exit !(d / 15 < 100) }' || die "S07 recovery rate"
      ;;
    S03)
      probe="$(curl -sf -X POST "${base}/api/payments/authorize" -H 'content-type: application/json' \
        -d '{"orderNumber":"JM-PROBE-REC","customerId":"user","amountCents":1000,"currency":"USD","cardToken":"tok_visa"}')"
      awk -v v="$(jq -r '.latencyMs' <<<"$probe")" 'BEGIN { exit !(v <= 100) }' || die "S03 recovery"
      ;;
    S12) awk -v v="$(metric authorizeMsP95 "$rec_status")" 'BEGIN { exit !(v <= 120) }' || die "S12 recovery" ;;
    S06)
      a="$(metric youngGcCount "$idle_status")"
      b="$(metric youngGcCount "$rec_status")"
      awk -v d="$((b - a))" 'BEGIN { exit !(d / 15 < 1) }' || die "S06 recovery"
      ;;
    S11) [[ "$(metric queueDepth "$rec_status")" == "0" ]] || die "S11 recovery" ;;
    S13)
      [[ "$(metric heartbeatStallsOver100ms "$idle_status")" == "$(metric heartbeatStallsOver100ms "$rec_status")" ]] || die "S13 recovery"
      ;;
    S05)
      [[ "$(metric cacheBytes "$rec_status")" -le $((70 * 1024 * 1024)) ]] || die "S05 bytes recovery"
      [[ "$(metric cacheEntries "$rec_status")" -le 1000 ]] || die "S05 entries recovery"
      ;;
    S04) [[ "$(metric leakedPools "$rec_status")" == "0" ]] || die "S04 recovery" ;;
  esac
}
recovery_check

curl -sf -X POST "${base}/internal/scenarios/${ID}/reset" >/dev/null
after="$(restarts)"
if grep -v ' 0$' <<<"$after" | grep -q .; then
  die "pod restart detected: $after"
fi
log "PASS $COMP $ID"
