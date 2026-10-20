# Ground truth

One JSON object per line, UTF-8, under `/data/ground-truth/<runId>.jsonl`. Manual starts outside a run go to `manual-<yyyyMMdd>.jsonl`. `seq` increases by 1 per line within a file. Each line is flushed and `FileChannel.force(false)` is called after the write.

## Record types

| type | When |
|---|---|
| `run_started` | A scripted run begins |
| `reset_all` | Conductor reset every hosting service before the run |
| `phase` | One per transition `RAMPING` / `HOLDING` / `RECOVERING` / `IDLE` (timestamps from the service status `startedAt` / `holdStartedAt` / `recoverStartedAt` / `endedAt`) |
| `guard` | Guard tripped or cleared |
| `scenario_summary` | Written at IDLE with `startTs`, `holdTs`, `recoverTs`, `endTs`, peak `metrics` |
| `run_completed` | Run reached `1800 × timeScale` seconds |
| `run_stopped` | Operator stopped the run |

## Phase field order

`schema`, `runId`, `seq`, `type`, `scenarioId`, `scenarioName`, `service`, `namespace`, `pod`, `container`, `subsystems`, `effects`, `phase`, `ts`, `tsEpochMs`, `offsetSeconds`, `plannedOffsetSeconds`, `intensity`, `seed`, `params`, `source`, `expected`.

- `schema` is always `jafra-mart/ground-truth/v1`.
- `expected` and `effects` are copied from `loadgen/src/main/resources/scripts/scenarios.json` (§13).
- `plannedOffsetSeconds` is the unscaled offset from §14 (timeScale 1.0). Actual `offsetSeconds` is measured from run start; ValidateRun compares it to `plannedOffsetSeconds × timeScale` within ±5 s.
- `source` is `script` for timeline-driven starts and `manual` otherwise.

## Example

```json
{"schema":"jafra-mart/ground-truth/v1","runId":"run-20261019T120000Z","seq":7,"type":"phase",
 "scenarioId":"S01","scenarioName":"cpu-flash-sale","service":"catalog-search","namespace":"jafra-mart",
 "pod":"catalog-search-6f7c9d8b5-x2k4q","container":"catalog-search","subsystems":[1],
 "effects":[{"service":"catalog-search","subsystems":[1]},{"service":"storefront","subsystems":[14]}],
 "phase":"HOLDING","ts":"2026-10-19T12:05:00.012Z","tsEpochMs":1792411500012,
 "offsetSeconds":300.01,"plannedOffsetSeconds":300,"intensity":1.0,"seed":-4623195318803573731,
 "params":{"fuzzyExpansions":4,"extraSearchRps":60},"source":"script",
 "expected":{"primary":["jdk.ContainerCPUThrottling","jdk.ExecutionSample(AP)"],"secondary":["jdk.CPULoad"]}}
```

Fetch a file with `GET http://localhost:30081/api/ground-truth?runId=<id>` (`application/x-ndjson`).
