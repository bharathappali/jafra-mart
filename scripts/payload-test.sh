#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/payload-test.sh <component>…

Port-forwards each component (local port 18000 + index) and runs
test/payloads/<component>/*.case. storefront uses http://127.0.0.1:30080 and
loadgen uses http://127.0.0.1:30081. Prints PASS/FAIL per case.
EOF
}

if [[ $# -eq 0 ]]; then
  usage
  exit 1
fi
if [[ "$1" == "--help" || "$1" == "-h" ]]; then
  usage
  exit 0
fi

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

fail=0
# bash 3.2 (macOS) has no associative arrays; store captures as NAME=VALUE lines
captures_file="$(mktemp)"
subst_captures() {
  local text="$1" line name value
  text="${text//\{utcDate\}/$(date -u +%Y%m%d)}"
  while IFS= read -r line || [[ -n "$line" ]]; do
    [[ -z "$line" ]] && continue
    name="${line%%=*}"
    value="${line#*=}"
    text="${text//\{$name\}/$value}"
  done < "$captures_file"
  printf '%s' "$text"
}

for c in "$@"; do
  if [[ "$c" == "storefront" ]]; then
    base="http://127.0.0.1:30080"
  elif [[ "$c" == "loadgen" ]]; then
    base="http://127.0.0.1:30081"
  else
    port="$(port_for "$c")"
    kubectl -n "$NAMESPACE" port-forward "svc/${c}" "${port}:8080" >/dev/null &
    PIDS+=("$!")
    base="http://127.0.0.1:${port}"
    for _ in 1 2 3 4 5 6 7 8 9 10; do
      if curl -sf -o /dev/null --max-time 1 "${base}/api/info" || curl -sf -o /dev/null --max-time 1 "${base}/q/health/live" || curl -sf -o /dev/null --max-time 1 "${base}/api/health"; then
        break
      fi
      sleep 0.3
    done
  fi
  jar="$(mktemp)"
  : > "$captures_file"
  shopt -s nullglob
  cases=( "$ROOT"/test/payloads/"$c"/*.case )
  shopt -u nullglob
  if [[ ${#cases[@]} -eq 0 ]]; then
    log "FAIL $c (no cases)"
    fail=1
    continue
  fi
  for file in "${cases[@]}"; do
    method="GET"
    path="/"
    expect="200"
    body=""
    headers=()
    expect_headers=()
    expect_jq=()
    capture_jq=()
    prefix=""
    sleep_s=0
    while IFS= read -r line || [[ -n "$line" ]]; do
      [[ -z "$line" || "$line" == \#* ]] && continue
      key="${line%% *}"
      value="${line#* }"
      case "$key" in
        METHOD) method="$value" ;;
        PATH) path="$value" ;;
        EXPECT_STATUS) expect="$value" ;;
        BODY) body="$value" ;;
        HEADER) headers+=("$value") ;;
        EXPECT_HEADER) expect_headers+=("$value") ;;
        EXPECT_JQ) expect_jq+=("$value") ;;
        CAPTURE_JQ) capture_jq+=("$value") ;;
        EXPECT_BYTES_PREFIX) prefix="$value" ;;
        SLEEP) sleep_s="$value" ;;
        *) die "bad case line in $file: $line" ;;
      esac
    done < "$file"
    if (( sleep_s > 0 )); then
      sleep "$sleep_s"
    fi
    path="$(subst_captures "$path")"
    out="$(mktemp)"
    hdr="$(mktemp)"
    args=(-sS -D "$hdr" -o "$out" -w '%{http_code}' -X "$method" -b "$jar" -c "$jar")
    for h in "${headers[@]:-}"; do
      args+=(-H "$h")
    done
    if [[ -n "$body" ]]; then
      args+=(-H 'content-type: application/json' --data "$body")
    fi
    code="$(curl "${args[@]}" "${base}${path}")" || code="000"
    name="$(basename "$file")"
    ok=1
    if [[ "$code" != "$expect" ]]; then
      ok=0
    fi
    if ((${#expect_jq[@]} > 0)); then
      jq_args=(-e)
      if grep -qi '^content-type:.*ndjson' "$hdr" || [[ "$path" == *ground-truth* ]]; then
        jq_args=(-s -e)
      fi
      for expr in "${expect_jq[@]}"; do
        expr="$(subst_captures "$expr")"
        if ! jq "${jq_args[@]}" "$expr" "$out" >/dev/null; then
          ok=0
        fi
      done
    fi
    if ((${#capture_jq[@]} > 0)) && [[ "$ok" == 1 ]]; then
      for cap in "${capture_jq[@]}"; do
        cname="${cap%% *}"
        cexpr="${cap#* }"
        # replace existing capture line if present
        if grep -q "^${cname}=" "$captures_file" 2>/dev/null; then
          grep -v "^${cname}=" "$captures_file" > "${captures_file}.tmp" || true
          mv "${captures_file}.tmp" "$captures_file"
        fi
        printf '%s=%s\n' "$cname" "$(jq -r "$cexpr" "$out")" >> "$captures_file"
      done
    fi
    if ((${#expect_headers[@]} > 0)); then
      for eh in "${expect_headers[@]}"; do
        hk="${eh%%:*}"
        hv="$(echo "${eh#*:}" | sed 's/^ *//')"
        if ! grep -i "^${hk}:" "$hdr" | grep -q "$hv"; then
          ok=0
        fi
      done
    fi
    if [[ -n "$prefix" ]]; then
      got="$(od -An -tx1 "$out" | tr -d ' \n' | tr 'a-f' 'A-F')"
      want="$(printf '%s' "$prefix" | tr 'a-f' 'A-F')"
      if [[ "$got" != "$want"* ]]; then
        ok=0
      fi
    fi
    if [[ "$ok" == 1 ]]; then
      log "PASS $c $name"
    else
      log "FAIL $c $name status=$code expected=$expect"
      fail=1
    fi
    rm -f "$out" "$hdr"
  done
  rm -f "$jar"
done
rm -f "$captures_file"

exit "$fail"
