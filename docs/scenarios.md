# Scenarios

Thirteen scenarios, S01–S13, cover Jafra subsystems 1–16. Subsystem 17 (endpoint latency) is not built in v1.

After Jafra adds JDK 25 `jdk.MethodTiming` / `jdk.MethodTrace` support, configure method timing filters on the REST resource methods of catalog-search and orders. Every scenario already produces endpoint-latency changes that will become labels for subsystem 17.

| Id | Name | Service | Subsystems |
|---|---|---|---|
| S01 | cpu-flash-sale | catalog-search | 1 |
| S02 | lock-hot-sku | inventory | 8 |
| S03 | socket-slow-payments | payments, orders | 14 |
| S04 | threads-burst-pools | orders | 9 |
| S05 | heap-recs-cache | recommendations | 2, 3 |
| S06 | gc-export-churn | reports-worker | 3, 2 |
| S07 | exceptions-coupon-storm | cart | 16 |
| S08 | metaspace-campaign-classes | pricing | 4, 13 |
| S09 | jit-deopt-codecache | pricing | 12, 5 |
| S10 | native-thumbnails | catalog-search | 6, 7 |
| S11 | file-fsync-invoices | reports-worker | 15 |
| S12 | vthread-pinning | payments, orders | 10 |
| S13 | safepoint-ttsp | reports-worker | 11 |

Each later branch fills in the mechanism, load, expected events, and app metric for the scenarios it owns.
