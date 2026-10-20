#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/jfr-verify.sh

Deploys the jfr-verify overlay, runs e2e-30min.sh at time scale 0.5, dumps JFR
on SIGTERM, and checks primary JDK events. Restarts are expected. Cleans the
namespace with cleanup.sh when the checks pass.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd kubectl
require_cmd java
require_cmd jfr

export JAFRA_RUN_KIND=jfr-verify
export E2E_KUSTOMIZE="$ROOT/manifests/overlays/jfr-verify"
log "jfr-verify artifacts will be under $ARTIFACTS"
"$ROOT/scripts/e2e-30min.sh" --time-scale 0.5

jvm=(catalog-search pricing inventory cart payments reports-worker recommendations orders storefront)
mkdir -p "$ARTIFACTS/jfr"

events_for() {
  case "$1" in
    catalog-search) echo "jdk.ContainerCPUThrottling jdk.NativeMemoryUsage jdk.NativeMemoryUsageTotal jdk.ResidentSetSize jdk.ContainerMemoryUsage" ;;
    pricing) echo "jdk.ClassLoadingStatistics jdk.ClassLoad jdk.ClassUnload jdk.MetaspaceSummary jdk.MetaspaceGCThreshold jdk.GarbageCollection jdk.OldGarbageCollection jdk.Deoptimization jdk.CodeCacheStatistics jdk.CompilerStatistics jdk.CompilerQueueUtilization" ;;
    inventory) echo "" ;;
    cart) echo "jdk.ExceptionStatistics" ;;
    payments) echo "jdk.VirtualThreadPinned" ;;
    orders) echo "jdk.SocketRead jdk.ThreadStart jdk.JavaThreadStatistics jdk.ThreadEnd" ;;
    recommendations) echo "jdk.GCHeapSummary jdk.ZYoungGarbageCollection jdk.ZOldGarbageCollection jdk.GarbageCollection" ;;
    reports-worker) echo "jdk.YoungGarbageCollection jdk.GarbageCollection jdk.GCHeapSummary jdk.PSHeapSummary jdk.SafepointBegin jdk.SafepointStateSynchronization jdk.ExecuteVMOperation" ;;
    storefront) echo "" ;;
    *) echo "" ;;
  esac
}

cat > "$ARTIFACTS/jfr/CountJfr.java" << 'EOF'
import java.nio.file.Path;
import java.util.LinkedHashMap;
import java.util.Map;
import jdk.jfr.consumer.RecordedEvent;
import jdk.jfr.consumer.RecordingFile;

public class CountJfr {
  public static void main(String[] args) throws Exception {
    Map<String, Long> counts = new LinkedHashMap<>();
    long socket = 0;
    long safepoint = 0;
    try (RecordingFile file = new RecordingFile(Path.of(args[0]))) {
      while (file.hasMoreEvents()) {
        RecordedEvent event = file.readEvent();
        String name = event.getEventType().getName();
        counts.merge(name, 1L, Long::sum);
        long ms = event.getDuration().toMillis();
        if ("jdk.SocketRead".equals(name) && ms >= 1000) {
          socket++;
        }
        if ("jdk.SafepointStateSynchronization".equals(name) && ms >= 100) {
          safepoint++;
        }
      }
    }
    counts.forEach((name, count) -> System.out.println(name + " " + count));
    System.out.println("FILTER jdk.SocketRead>=1000ms " + socket);
    System.out.println("FILTER jdk.SafepointStateSynchronization>=100ms " + safepoint);
  }
}
EOF

ok=true
report="$ROOT/docs/jfr-verification.md"
{
  echo "# JFR verification"
  echo
  echo "Run artifacts: \`$ARTIFACTS\`."
  echo "Recording: \`-XX:StartFlightRecording=name=verify,settings=profile,filename=/tmp/verify-%t.jfr,dumponexit=true,maxsize=150m,+jdk.SafepointStateSynchronization#enabled=true,+jdk.SafepointLatency#enabled=true,+jdk.ClassLoad#enabled=true,+jdk.ClassLoad#threshold=0ms,+jdk.ClassUnload#enabled=true,+jdk.JavaExceptionThrow#enabled=true,+jdk.FileForce#threshold=0ms,+jdk.VirtualThreadPinned#threshold=20ms\`."
  echo
  echo "| Pod | Check | Count | Result |"
  echo "|---|---|---:|---|"
} > "$report"

for c in "${jvm[@]}"; do
  log "dumping JFR for $c"
  pod="$(kubectl -n "$NAMESPACE" get pod -l "app.kubernetes.io/name=${c}" -o jsonpath='{.items[0].metadata.name}')"
  before="$(kubectl -n "$NAMESPACE" get pod "$pod" -o jsonpath="{.status.containerStatuses[?(@.name==\"${c}\")].restartCount}")"
  kubectl -n "$NAMESPACE" exec "deploy/${c}" -c "$c" -- sh -c 'kill -TERM 1' >/dev/null 2>&1 || true
  ready=false
  for _ in $(seq 1 90); do
    now="$(kubectl -n "$NAMESPACE" get pod "$pod" -o jsonpath="{.status.containerStatuses[?(@.name==\"${c}\")].restartCount}" 2>/dev/null || echo 0)"
    cond="$(kubectl -n "$NAMESPACE" get pod "$pod" -o jsonpath='{.status.conditions[?(@.type=="Ready")].status}' 2>/dev/null || echo False)"
    if [[ "${now:-0}" -gt "${before:-0}" && "$cond" == "True" ]]; then
      ready=true
      break
    fi
    sleep 2
  done
  if [[ "$ready" != true ]]; then
    echo "| ${c} | container restart after SIGTERM |  | FAIL |" >> "$report"
    ok=false
    continue
  fi
  mkdir -p "$ARTIFACTS/jfr/${c}"
  kubectl cp -n "$NAMESPACE" -c "$c" "${pod}:/tmp/." "$ARTIFACTS/jfr/${c}/tmp" || true
  jfr_file=""
  while IFS= read -r candidate; do
    [[ -n "$candidate" ]] || continue
    if [[ -z "$jfr_file" ]] || [[ "$(wc -c < "$candidate")" -gt "$(wc -c < "$jfr_file")" ]]; then
      jfr_file="$candidate"
    fi
  done < <(find "$ARTIFACTS/jfr/${c}" -name '*.jfr' -type f 2>/dev/null || true)
  if [[ -z "${jfr_file:-}" ]]; then
    echo "| ${c} | jfr file | 0 | FAIL |" >> "$report"
    ok=false
    continue
  fi
  jfr summary "$jfr_file" > "$ARTIFACTS/jfr/${c}/summary.txt"
  java "$ARTIFACTS/jfr/CountJfr.java" "$jfr_file" > "$ARTIFACTS/jfr/${c}/counts.txt"
  for ev in $(events_for "$c"); do
    count="$(awk -v ev="$ev" '$1==ev {print $2; found=1; exit} END {if (!found) print 0}' "$ARTIFACTS/jfr/${c}/summary.txt")"
    if [[ "${count:-0}" -ge 1 ]]; then
      echo "| ${c} | ${ev} | ${count} | PASS |" >> "$report"
    else
      echo "| ${c} | ${ev} | ${count:-0} | FAIL |" >> "$report"
      ok=false
    fi
  done
  if [[ -z "$(events_for "$c")" ]]; then
    echo "| ${c} | no primary JFR events in §13 |  | PASS |" >> "$report"
  fi
done

check_min() {
  local pod="$1" label="$2" file="$3" min="$4"
  local count
  count="$(awk -v ev="$label" '$1==ev {print $2; exit}' "$ARTIFACTS/jfr/${pod}/counts.txt" || echo 0)"
  if [[ -z "$count" ]]; then
    count=0
  fi
  if [[ "$count" -ge "$min" ]]; then
    echo "| ${pod} | ${label} ≥ ${min} | ${count} | PASS |" >> "$report"
  else
    echo "| ${pod} | ${label} ≥ ${min} | ${count} | FAIL |" >> "$report"
    ok=false
  fi
}

check_filter() {
  local pod="$1" label="$2" min="$3"
  local count
  count="$(awk -v ev="$label" '$0 ~ "^FILTER " ev " " {print $3; exit}' "$ARTIFACTS/jfr/${pod}/counts.txt" || echo 0)"
  if [[ -z "$count" ]]; then
    count=0
  fi
  if [[ "$count" -ge "$min" ]]; then
    echo "| ${pod} | ${label} ≥ ${min} | ${count} | PASS |" >> "$report"
  else
    echo "| ${pod} | ${label} ≥ ${min} | ${count} | FAIL |" >> "$report"
    ok=false
  fi
}

check_min pricing jdk.Deoptimization "$ARTIFACTS/jfr/pricing/counts.txt" 1000
check_min pricing jdk.ClassUnload "$ARTIFACTS/jfr/pricing/counts.txt" 1000
check_min payments jdk.VirtualThreadPinned "$ARTIFACTS/jfr/payments/counts.txt" 50
check_filter orders "jdk.SocketRead>=1000ms" 50
check_filter reports-worker "jdk.SafepointStateSynchronization>=100ms" 10

if [[ "$ok" != true ]]; then
  echo >> "$report"
  echo "Result: FAIL" >> "$report"
  die "jfr-verify failed; see $report"
fi
echo >> "$report"
echo "Result: PASS" >> "$report"
log "jfr-verify passed; cleaning namespace"
"$ROOT/scripts/cleanup.sh"
log "jfr-verify ok"
