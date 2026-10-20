# Runbook

## Build report

Branches, in PR order. Merge each into `main` with a merge commit.

| Branch | Head |
| --- | --- |
| `00-foundation` | `90bf3fa` |
| `01-catalog-search` | `fde6ff8` |
| `02-pricing` | `5ed5f2f` |
| `03-inventory` | `3b412a0` |
| `04-cart` | `bf8434d` |
| `05-payments` | `9b1c43e` |
| `06-reports-worker` | `19881c6` |
| `07-recommendations` | `16262ca` |
| `08-orders` | `4177f41` |
| `09-storefront` | `fee0518` |
| `10-loadgen` | `8f594a5` |
| `11-e2e-30min` | this commit (parent `8f594a5`) |

Images `quay.io/bharathappali/jafra-mart:v1-<component>`, manifest list plus both platforms:

| Component | Manifest list | linux/amd64 | linux/arm64 |
| --- | --- | --- | --- |
| catalog-search | `sha256:6ee3efb0b30801a4dd4c7ca5a7e7a69d3239dd6747ec989edc1703529a531880` | `sha256:cc79d01cc260120447af200c4bc6d17f8ac0834bd09d2719307b6caaa616401e` | `sha256:a51d78c48a27d9bf41f9df4b71d2421a59c75ee89c38227fb3b503e6f2409c3d` |
| pricing | `sha256:62be1cde6ffabf6ae0798ba167c2bf3194a1da91e015e407cc4c34d283b5ef43` | `sha256:48d0a5041741faf175f8095dfad889e3681a55f523ea2011581d1d5252302bf9` | `sha256:b4919a5e4d4faac68d31438d2ca2c93c7a8ad198685112db530b29b037536672` |
| inventory | `sha256:3f2b10028cb831b4a7d8ece74b9db4f9398ca7fa8a156bacfd9adcac83246c57` | `sha256:39f4f412646bcc3663d8d9d23283d519d6dbb481f396280e05895831aacfeca5` | `sha256:6f560e962f71f99ba7a61d00ef0b23cc1eb314278ec974623f07a56142fe9624` |
| cart | `sha256:d983d05208bb8e9be36ac343b6585944f74e58b675aea9149b40a3b78b866c16` | `sha256:db41090ee609a0d28a2d81b9d74958e68d2464baf1964ec0f8d7e9c7326ebb30` | `sha256:f4dfbe2b7aea11f8f2c3b33e18410c09d2ac57c3f11789d54df93209fd2aa1ef` |
| payments | `sha256:f14f7cf71839183d1acdac4f57255fbfe898db3754ae911233e729da2094ef31` | `sha256:e25171abc769b8a51e1fffb92bd010d49daeeca1e9f5e79c757b5234840c6560` | `sha256:bfc14c099c6c12b0e677f45de99b3f9b3a0d7b9b65888c187f68b66796bff670` |
| reports-worker | `sha256:fda8515786af61063cc899c46af4521c9d3a647c7693f2b17f28d8fdbfdb6bc9` | `sha256:56c729129f03de47d8d1f5767623cb6ed84bc475b65283e18d323258b558b16d` | `sha256:3919a34e6a82964884e1b8ee7ded75d15f041ad9cd71a2deb8ba542bfaef0d97` |
| recommendations | `sha256:bf1d72cf0bf4cfae0f6959a956c5a4cc47aaabe0adaa4649c4aa9ed7658c93ba` | `sha256:06f24023aefcb6511173935bc6d85045e19bcaab1255f62feeff93217521dd95` | `sha256:ea5f0de8de9b0e51c7367bcd7c574032fb268e137921ae61738f4f6a0171d0a9` |
| orders | `sha256:253ff6df2f00e66fc93c6898c6d30eddddc3f42913cf999f2947baacd56551a1` | `sha256:c8349c97711be5c272cbe7b16dfc60f68292e14e7abdb0c9b552f1fe2655790b` | `sha256:1e079d8e0464a0c5117310ffe465c88ab7de837b492c52d04e08eb418f413cb8` |
| storefront | `sha256:f2bbc11909c4ceec05fe61a547755d65855840b094817153136b93eada2f422c` | `sha256:72233e0093f87f9ea56ea48540e3bbd84979e259913c6f0f1ff90046889711a8` | `sha256:3fb45c146195d406e954458dee9d92872bea986fb433660425abacbee7ce4e68` |
| loadgen | `sha256:a91eb3514772c498436c8f4188462489150fcb0c5b8e10861f27e957e5fb7d8c` | `sha256:f0458259a412d4f43d5cd0c8dce1e08cfe546b80c197b4d8808ee0a68b21d9f5` | `sha256:1c860296f7c962117e53005d82f13e004278413e46e364f823c80888d46cf5b3` |

e2e time scale 1.0 passed: `run-artifacts/20261020T101425Z`, run `run-20261020T102302Z`, restarts 0, no OOMKilled. Structure and timing for S01–S13 passed. Hold and recovery:

| Check | Result |
| --- | --- |
| stability restarts / OOM / headroom (all 9 JVM pods) | PASS |
| load health and storefront readiness | PASS |
| baseline catalog throttle delta 6, checkout p50 worst 59 ms | PASS |
| S01 throttle delta 1764 / 616978466 µs, recovery fuzzyExpansions 1 | PASS |
| S02 mid-hold lock p95 80.8 ms, recovery 0 | PASS |
| S03 checkout p50 1216 ms, recovery baseLatency 40 ms | PASS |
| S04 threadCount 374, recovery 54 | PASS |
| S05 cache 238.1 MiB, recovery 24.9 MiB / 398 entries | PASS |
| S06 exports delta 234, young GC 5.3/s vs baseline 0, recovery 0 | PASS |
| S07 exceptions 1153.8/s, recovery 2.1/s | PASS |
| S08 hidden classes 6000, recovery 0 and metaspace 49 vs hold 95 | PASS |
| S09 code cache 78.7% and pipes delta 3493, recovery live pipes 0 | PASS |
| S10 off-heap 102.3 MiB and catalog growth 288.9 MiB, recovery off-heap 4 / disk 0 | PASS |
| S11 fsyncs delta 55152, recovery queue 0 | PASS |
| S12 pinned delta 449 and checkout p95 1562 vs baseline 72, recovery authorize p95 47 | PASS |
| S13 stalls delta 22, recovery no new stalls | PASS |

Peak working set vs limit is in `docs/sizing.md`. catalog-search is 425.0 / 768 MiB after two +32Mi bumps and `MALLOC_ARENA_MAX=2`.

jfr-verify passed at time scale 0.5: `run-artifacts/20261020T105441Z`. Restarts after SIGTERM are expected. Full event table: `docs/jfr-verification.md`. Threshold rows: pricing Deoptimization 1065 (≥ 1000), ClassUnload 11493 (≥ 1000), payments VirtualThreadPinned 2594 (≥ 50), orders SocketRead ≥ 1s count 529 (≥ 50), reports-worker SafepointStateSynchronization ≥ 100ms count 22 (≥ 10). Result: PASS. `cleanup.sh` deleted namespace `jafra-mart` afterwards.

Tie-breaks are `docs/decisions-log.md`. On this branch: podman pids limit and `DefaultTasksMax=8192`; burst pools created off the notifier lock; catalog search read timeout 500 ms; hot-SKU park 6 ms and a 10 s sample window; conductor clock split from status polls; catalog-search memory 512/704 → 576/768 and `MALLOC_ARENA_MAX=2`; pipe `mix` is four 128-op blocks, warmup 22,000, trim-and-collect at 82% code cache; ValidateRun accepts exponent numbers; kustomize load restrictor and the jfr/jafra overlays.

How Stark runs it: `scripts/kind-up.sh && scripts/kind-load.sh && scripts/deploy.sh && scripts/smoke-test.sh`, open `http://localhost:30080` (admin/admin → Scenarios → Run 30-min script), or `scripts/e2e-30min.sh`.

How to deploy from quay instead of local images: skip `kind-load.sh`; `deploy.sh` with `imagePullPolicy: IfNotPresent` pulls the public images.

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
