# Decisions

- §9.1: three product tags are drawn by rejection sampling from `ADJ ∪ MAT` (the spec does not name the draw algorithm).
- §6 / §8.1: `GuardStatus.java` is a top-level type because §8.1 declares `public record GuardStatus`; the brace list omitted the filename.
- §8.4: `ScenarioRegistry.ControlException` is nested in `ScenarioRegistry` so adapters can map 404/400/409 without a file outside the §6 list.
- §8.2: `PhaseMachine` methods take an `Instant` so unit tests drive a fake clock; the `scenario-clock` thread calls `tick(Instant.now())` every 1000 ms.
- §8.3: `SafetyGuard.Sampler` is the fake-sampler seam. Heap trip uses the minimum `used` still inside the trailing 10 s window, including a window that is shorter than 10 s.
- §8.4: `PodIdentity.namespace()` returns `jafra-mart` when `POD_NAMESPACE` is unset.
- §17: storefront and loadgen payload tests use NodePorts 30080 and 30081 because §19.2 names those URLs. Other components use port-forward `18000 + index`.
- §19.3: `scenario-check.sh` embeds the MiniLoad shape for each scenario so it can be invoked with only the component and id. Extra arguments replace that shape.
- §7.2: plain-module tests use JUnit assertions. AssertJ is allowed and not required.
- §17: `MiniLoad` uses a 15 s HTTP request timeout, matching the loadgen client timeout in §3.3.
- §18.1: `git config user.signingkey` is unset. `git commit -S` selects the secret key whose uid matches `user.email` (`DED0F574DDB444527D3C36FFB91F8CFEAC8627DD`). Git config is not modified.
