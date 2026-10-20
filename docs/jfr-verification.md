# JFR verification

Run artifacts: `run-artifacts/20261020T105441Z`.
Recording: `-XX:StartFlightRecording=name=verify,settings=profile,filename=/tmp/verify-%t.jfr,dumponexit=true,maxsize=150m,+jdk.SafepointStateSynchronization#enabled=true,+jdk.SafepointLatency#enabled=true,+jdk.ClassLoad#enabled=true,+jdk.ClassLoad#threshold=0ms,+jdk.ClassUnload#enabled=true,+jdk.JavaExceptionThrow#enabled=true,+jdk.FileForce#threshold=0ms,+jdk.VirtualThreadPinned#threshold=20ms`.

| Pod | Check | Count | Result |
|---|---|---:|---|
| catalog-search | jdk.ContainerCPUThrottling | 34 | PASS |
| catalog-search | jdk.NativeMemoryUsage | 28215 | PASS |
| catalog-search | jdk.NativeMemoryUsageTotal | 1045 | PASS |
| catalog-search | jdk.ResidentSetSize | 1045 | PASS |
| catalog-search | jdk.ContainerMemoryUsage | 34 | PASS |
| pricing | jdk.ClassLoadingStatistics | 1070 | PASS |
| pricing | jdk.ClassLoad | 16898 | PASS |
| pricing | jdk.ClassUnload | 11493 | PASS |
| pricing | jdk.MetaspaceSummary | 140 | PASS |
| pricing | jdk.MetaspaceGCThreshold | 16 | PASS |
| pricing | jdk.GarbageCollection | 70 | PASS |
| pricing | jdk.OldGarbageCollection | 55 | PASS |
| pricing | jdk.Deoptimization | 1065 | PASS |
| pricing | jdk.CodeCacheStatistics | 4 | PASS |
| pricing | jdk.CompilerStatistics | 1070 | PASS |
| pricing | jdk.CompilerQueueUtilization | 428 | PASS |
| inventory | no primary JFR events in §13 |  | PASS |
| cart | jdk.ExceptionStatistics | 1085 | PASS |
| payments | jdk.VirtualThreadPinned | 2594 | PASS |
| reports-worker | jdk.YoungGarbageCollection | 527 | PASS |
| reports-worker | jdk.GarbageCollection | 555 | PASS |
| reports-worker | jdk.GCHeapSummary | 1110 | PASS |
| reports-worker | jdk.PSHeapSummary | 1110 | PASS |
| reports-worker | jdk.SafepointBegin | 598 | PASS |
| reports-worker | jdk.SafepointStateSynchronization | 598 | PASS |
| reports-worker | jdk.ExecuteVMOperation | 674 | PASS |
| recommendations | jdk.GCHeapSummary | 114 | PASS |
| recommendations | jdk.ZYoungGarbageCollection | 57 | PASS |
| recommendations | jdk.ZOldGarbageCollection | 57 | PASS |
| recommendations | jdk.GarbageCollection | 57 | PASS |
| orders | jdk.SocketRead | 12674 | PASS |
| orders | jdk.ThreadStart | 253 | PASS |
| orders | jdk.JavaThreadStatistics | 1117 | PASS |
| orders | jdk.ThreadEnd | 200 | PASS |
| storefront | no primary JFR events in §13 |  | PASS |
| pricing | jdk.Deoptimization ≥ 1000 | 1065 | PASS |
| pricing | jdk.ClassUnload ≥ 1000 | 11493 | PASS |
| payments | jdk.VirtualThreadPinned ≥ 50 | 2594 | PASS |
| orders | jdk.SocketRead>=1000ms ≥ 50 | 529 | PASS |
| reports-worker | jdk.SafepointStateSynchronization>=100ms ≥ 10 | 22 | PASS |

Result: PASS
