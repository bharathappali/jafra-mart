# Sizing

Kind node: at least 6 CPUs and 8 GiB. App CPU requests sum to 3.80. Memory limits sum to 5856Mi. Headroom rule: at every sample, `memory.current - inactive_file` must be at most `limit - 96Mi`. If a pod fails that rule, raise only that pod's memory request and limit by 32Mi and record the change here. Total limits must stay at or below 6016Mi.

Measured peak working set is filled in by branch 11.
