# Timeline

`default-30m` is 1800 seconds at time scale 1.0. Baseline load runs the whole time. The first four minutes are warm-up. The last minute is cool-down. Phase seconds sent to services are `max(5, round(seconds × timeScale))`; entry starts at `startAt × timeScale`.

| Scenario | Service | Subsystems | Ramp start | Hold start | Recover start | End | Hold |
|---|---|---|---|---|---|---|---|
| — baseline — | all | — | 00:00 | — | — | 04:00 | JIT warm-up |
| S01 cpu-flash-sale | catalog-search | 1 | 04:00 | 05:00 | 08:00 | 09:00 | 3:00 |
| S02 lock-hot-sku | inventory | 8 | 05:00 | 05:30 | 09:00 | 10:00 | 3:30 |
| S03 socket-slow-payments | payments → orders | 14 | 08:00 | 09:00 | 12:00 | 13:00 | 3:00 |
| S04 threads-burst-pools | orders | 9 | 10:00 | 11:00 | 14:00 | 15:00 | 3:00 |
| S05 heap-recs-cache | recommendations | 2, 3 | 12:00 | 14:00 | 17:00 | 18:00 | 3:00 |
| S06 gc-export-churn | reports-worker | 3, 2 | 14:00 | 15:00 | 17:00 | 18:00 | 2:00 |
| S07 exceptions-coupon-storm | cart | 16 | 16:00 | 16:30 | 19:30 | 20:00 | 3:00 |
| S08 metaspace-campaign-classes | pricing | 4, 13 | 18:00 | 19:00 | 22:00 | 23:00 | 3:00 |
| S09 jit-deopt-codecache | pricing | 12, 5 | 19:00 | 20:00 | 23:00 | 24:00 | 3:00 |
| S10 native-thumbnails | catalog-search | 6, 7 | 20:00 | 21:00 | 24:00 | 25:00 | 3:00 |
| S11 file-fsync-invoices | reports-worker | 15 | 22:00 | 22:30 | 25:30 | 26:00 | 3:00 |
| S12 vthread-pinning | payments → orders | 10 | 23:00 | 23:30 | 26:30 | 27:00 | 3:00 |
| S13 safepoint-ttsp | reports-worker | 11 | 26:00 | 26:30 | 28:30 | 29:00 | 2:00 |
| — cool-down — | all | — | 29:00 | — | — | 30:00 | baseline |

## Intentional overlaps

- **Flash sale** — S01 + S02 (two pods, one business event).
- **Slow dependency → thread growth** — S03 + S04 on orders.
- **Retention vs churn** — S05 (ZGC recommendations) + S06 (Parallel reports-worker).
- **Class churn feeds the JIT** — S08 + S09 on pricing (the only same-pod overlap).
- **Two memory stories at once** — S09 + S10.
- **Pinning vs plain latency** — S03 (08–13) vs S12 (23–27): same checkout victim, different root cause.

Same-pod scenarios never overlap except S08 + S09.

Script file: `loadgen/src/main/resources/scripts/default-30m.json`. Catalog metadata: `loadgen/src/main/resources/scripts/scenarios.json`.
