# Sizing

Kind node: at least 6 CPUs and 8 GiB. App CPU requests sum to 3.80. Memory limits sum to 5920Mi. Headroom rule: at every sample, `memory.current - inactive_file` must be at most `limit - 96Mi`. If a pod fails that rule, raise only that pod's memory request and limit by 32Mi and record the change here. Total limits must stay at or below 6016Mi.

catalog-search request 512Mi→544Mi and limit 704Mi→736Mi after `run-artifacts/20261020T060228Z` (working set 627.6MiB, ceiling 608MiB). Raised again, request 544Mi→576Mi and limit 736Mi→768Mi, after `run-artifacts/20261020T081047Z` (working set 665.0MiB, ceiling 640MiB). The following run `run-artifacts/20261020T085133Z` peaked at 726.3MiB against ceiling 672MiB because glibc arenas retained freed thumbnail buffers and anonymous memory tracked the limit. `MALLOC_ARENA_MAX=2` is set on that container; the limits stay at 576/768. Total limits 5920Mi.

Measured peak working set on the passing time-scale 1.0 run `run-artifacts/20261020T101425Z` (`run-20261020T102302Z`). Working set is `memory.current − inactive_file`. Limits are the container memory limits after the catalog-search bumps above. Ceiling is limit − 96Mi.

| Pod | Peak working set | Limit | Ceiling |
| --- | ---: | ---: | ---: |
| catalog-search | 425.0MiB | 768Mi | 672Mi |
| pricing | 313.2MiB | 576Mi | 480Mi |
| inventory | 248.4MiB | 448Mi | 352Mi |
| cart | 236.3MiB | 352Mi | 256Mi |
| payments | 227.4MiB | 384Mi | 288Mi |
| reports-worker | 429.4MiB | 704Mi | 608Mi |
| recommendations | 573.6MiB | 832Mi | 736Mi |
| orders | 389.0MiB | 640Mi | 544Mi |
| storefront | 382.4MiB | 576Mi | 480Mi |

loadgen and postgres are not in the 5-second cgroup sample (that loop is the 9 JVM services). No pod was OOMKilled and no container restarted on this run.
