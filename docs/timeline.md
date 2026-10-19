# Timeline

`default-30m` is 1800 seconds at time scale 1.0. Baseline load runs the whole time. The first four minutes are warm-up. The last minute is cool-down.

| Scenario | Service | Ramp | Hold | Recover | End |
|---|---|---|---|---|---|
| S01 cpu-flash-sale | catalog-search | 04:00 | 05:00 | 08:00 | 09:00 |
| S02 lock-hot-sku | inventory | 05:00 | 05:30 | 09:00 | 10:00 |
| S03 socket-slow-payments | payments, orders | 08:00 | 09:00 | 12:00 | 13:00 |
| S04 threads-burst-pools | orders | 10:00 | 11:00 | 14:00 | 15:00 |
| S05 heap-recs-cache | recommendations | 12:00 | 14:00 | 17:00 | 18:00 |
| S06 gc-export-churn | reports-worker | 14:00 | 15:00 | 17:00 | 18:00 |
| S07 exceptions-coupon-storm | cart | 16:00 | 16:30 | 19:30 | 20:00 |
| S08 metaspace-campaign-classes | pricing | 18:00 | 19:00 | 22:00 | 23:00 |
| S09 jit-deopt-codecache | pricing | 19:00 | 20:00 | 23:00 | 24:00 |
| S10 native-thumbnails | catalog-search | 20:00 | 21:00 | 24:00 | 25:00 |
| S11 file-fsync-invoices | reports-worker | 22:00 | 22:30 | 25:30 | 26:00 |
| S12 vthread-pinning | payments, orders | 23:00 | 23:30 | 26:30 | 27:00 |
| S13 safepoint-ttsp | reports-worker | 26:00 | 26:30 | 28:30 | 29:00 |

Intentional overlaps: flash sale S01+S02; slow dependency then thread growth S03+S04; retention versus churn S05+S06; class churn feeding the JIT S08+S09; two memory stories S09+S10; pinning versus plain latency S03 versus S12. Same-pod scenarios never overlap except S08+S09.
