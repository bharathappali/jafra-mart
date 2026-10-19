# Runbook

## Build report

Filled in after branch 11.

## Troubleshooting

1. **kind cannot find podman / tries docker** — export `KIND_EXPERIMENTAL_PROVIDER=podman` (lib.sh does). Check `kind get clusters`.
2. **macOS (Apple silicon or Intel)** — kind with podman needs a rootful machine with enough resources: `podman machine stop; podman machine set --rootful --cpus 6 --memory 8192; podman machine start`. Node architecture equals the machine architecture (arm64 on Apple silicon); `kind-load.sh` builds `linux/$NATIVE_ARCH`, so detect arch from `podman info --format '{{.Host.Arch}}'` on macOS instead of `uname -m` (lib.sh: on Darwin use podman info).
3. **Rootless podman on Linux** — kind needs cgroup v2 with CPU delegation; if `kind create cluster` fails, run `systemd-run --scope --user -p Delegate=yes kind create cluster --config kind/kind-config.yaml` (lib.sh retries this way automatically once).
4. **`kind load` fails with "no unpack platforms defined" or the pod shows `ErrImageNeverPull`/`ErrImagePull` for a local image** — the archive must be single-platform: always load the image built by `kind-load.sh` (`--platform linux/$NATIVE_ARCH`), never the manifest list. Re-run `kind-load.sh --components <c>`. Verify with `podman exec jaframart-control-plane crictl images | grep jafra-mart`.
5. **Do not pipe `podman save | kind load`** — concurrent podman commands can deadlock; scripts save to a file first.
6. **Multi-arch build errors about `exec format error`** — a `RUN` crept into a runtime stage; runtime stages must have none (§15).
7. **`podman manifest push` 401/403** — run `podman login quay.io` yourself; the repo `bharathappali/jafra-mart` must allow the logged-in user to push.
8. **NodePorts not reachable on macOS** — the `extraPortMappings` are published by the podman machine; use `http://localhost:30080`. If busy, stop whatever uses ports 30080/30081.
9. **Pods Pending with `Insufficient cpu`** — node allocatable CPU < 4.8; give the podman machine 6 CPUs (§4.1). Do not lower requests.
10. **Probe restarts during scenarios** — probes must not call downstream services; liveness tolerates 60 s of slowness (§16.3). A restart is a bug: find the blocking call on the health path.
11. **ContainerCPUThrottling numbers look zero** — check `kubectl exec deploy/catalog-search -- cat /sys/fs/cgroup/cpu.stat`; if `nr_throttled` stays 0 during S01, the node may not enforce CFS quota (rootless without CPU delegation, see 3).
12. **Orders cannot reach Postgres** — the Secret must exist before orders starts; `deploy.sh` creates it. `kubectl -n jafra-mart get secret postgres-credentials`.
13. **Clock drift in ground truth** — all pods share the node clock in kind; offsets > 5 s mean the conductor was CPU-starved: check loadgen throttling.
