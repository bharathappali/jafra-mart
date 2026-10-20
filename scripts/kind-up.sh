#!/usr/bin/env bash
set -euo pipefail
ROOT="$(cd "$(dirname "${BASH_SOURCE[0]}")/.." && pwd)"
cd "$ROOT"
# shellcheck source=lib.sh
source "$ROOT/scripts/lib.sh"

usage() {
  cat <<'EOF'
Usage: scripts/kind-up.sh

Creates the kind cluster "jaframart" when it is missing, then waits until the
node is Ready. Fails when allocatable CPU is below 5 or memory is below 7 GiB.
If the cluster already exists, prints that fact and exits 0.
EOF
}

while [[ $# -gt 0 ]]; do
  case "$1" in
    --help|-h) usage; exit 0 ;;
    *) die "unknown argument: $1" ;;
  esac
done

require_cmd podman
require_cmd kind
require_cmd kubectl

# podman --pids-limit defaults to 2048. systemd inside the node then sets
# DefaultTasksMax to 15% of that (307) on every container scope. S04 needs
# ~400 tasks (40 pools × 8 threads plus the JVM). 307 returns EAGAIN and the
# kubelet restarts orders before threadCount can reach 300.
ensure_container_pids() {
  local node="${CLUSTER}-control-plane"
  if ! podman container exists "$node" >/dev/null 2>&1; then
    return 0
  fi
  local limit
  limit="$(podman inspect --format '{{.HostConfig.PidsLimit}}' "$node")"
  if [[ "${limit:-0}" -lt 65536 ]]; then
    log "raising $node pids limit from ${limit} to 65536"
    podman update --pids-limit 65536 "$node" >/dev/null
  fi
  podman exec "$node" sh -c '
    conf=/etc/systemd/system.conf.d/10-jaframart-tasks.conf
    if [ ! -f "$conf" ] || ! grep -qx "DefaultTasksMax=8192" "$conf"; then
      mkdir -p /etc/systemd/system.conf.d
      printf "[Manager]\nDefaultTasksMax=8192\n" > "$conf"
      systemctl daemon-reexec
    fi
  '
}

if kind get clusters | grep -qx "$CLUSTER"; then
  ensure_container_pids
  log "cluster $CLUSTER already exists"
  exit 0
fi

create_cluster() {
  if kind create cluster --config kind/kind-config.yaml; then
    return 0
  fi
  if [[ "$(uname -s)" == "Linux" ]]; then
    log "retrying kind create with cgroup CPU delegation"
    systemd-run --scope --user -p Delegate=yes kind create cluster --config kind/kind-config.yaml
    return 0
  fi
  return 1
}

create_cluster
ensure_container_pids
kubectl wait --for=condition=Ready node --all --timeout=180s
cpu="$(kubectl get nodes -o jsonpath='{.items[0].status.allocatable.cpu}')"
mem="$(kubectl get nodes -o jsonpath='{.items[0].status.allocatable.memory}')"
log "allocatable cpu=$cpu memory=$mem"
cpu_m="$(cpu_millis "$cpu")"
mem_b="$(mem_bytes "$mem")"
if [[ "$cpu_m" -lt 5000 ]]; then
  die "allocatable CPU ${cpu} is below 5"
fi
if [[ "$mem_b" -lt $((7 * 1024 * 1024 * 1024)) ]]; then
  die "allocatable memory ${mem} is below 7 GiB"
fi
