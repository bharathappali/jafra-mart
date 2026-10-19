package com.jaframart.scenario;

import java.util.List;
import java.util.Map;

public record ScenarioStatus(String id, String name, String service, String pod, String container,
                             List<Integer> subsystems, ScenarioState state, double intensity, double currentIntensity,
                             int rampSeconds, int holdSeconds, int recoverSeconds, long seed, String runId,
                             String startedAt, String holdStartedAt, String recoverStartedAt, String endedAt,
                             Map<String, Object> params, Map<String, Object> metrics, GuardStatus guard) {}
