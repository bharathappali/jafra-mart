package com.jaframart.scenario;

import java.util.List;
import java.util.Map;

public interface Scenario {
  String id();

  String name();

  List<Integer> subsystems();

  int defaultRampSeconds();

  int defaultHoldSeconds();

  int defaultRecoverSeconds();

  Map<String, Object> params();

  void onStart(ScenarioContext ctx);

  void onTick(ScenarioContext ctx, double currentIntensity);

  void onReset(ScenarioContext ctx);

  Map<String, Object> metrics();
}
