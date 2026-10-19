# Ground truth

One JSON object per line, UTF-8, under `/data/ground-truth/<runId>.jsonl`. Manual starts outside a run go to `manual-<yyyyMMdd>.jsonl`. `seq` increases by 1 per line. Each line is flushed and `FileChannel.force(false)` is called after the write.

Record types: `run_started`, `reset_all`, `phase`, `guard`, `scenario_summary`, `run_completed`, `run_stopped`.

Field order for a phase record:

`schema`, `runId`, `seq`, `type`, `scenarioId`, `scenarioName`, `service`, `namespace`, `pod`, `container`, `subsystems`, `effects`, `phase`, `ts`, `tsEpochMs`, `offsetSeconds`, `plannedOffsetSeconds`, `intensity`, `seed`, `params`, `source`, `expected`.

`schema` is `jafra-mart/ground-truth/v1`. `expected` and `effects` are copied from `loadgen/src/main/resources/scripts/scenarios.json`.
