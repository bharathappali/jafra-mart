# Jafra compatibility

The applications do not set `JAVA_TOOL_OPTIONS`, do not mount `/jafra-agent` or `/jfr-data`, and do not create a volume named `jafra-recordings`. JVM flags live in `JAVA_OPTS`. Dynamic attach stays enabled. `/tmp` is a writable emptyDir. The JVM runs as UID 1001, GID 0, and is PID 1.

Pods ship without Jafra opt-in labels. To opt in later, add this to the 9 JVM service Deployments (not loadgen, not postgres) via `manifests/overlays/jafra` (created in branch 11, never applied by the tests):

```yaml
metadata:
  labels: { jafra.io/enabled: "true", jafra.io/mode: "continuous" }
  annotations: { jafra.io/containers: "<component>", jafra.io/chunktime: "5s" }
```
