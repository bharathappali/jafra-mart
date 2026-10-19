package com.jaframart.scenario;

public final class PodIdentity {
  private PodIdentity() {}

  public static String pod() {
    String pod = System.getenv("POD_NAME");
    if (pod != null && !pod.isBlank()) {
      return pod;
    }
    String host = System.getenv("HOSTNAME");
    return host == null || host.isBlank() ? "unknown" : host;
  }

  public static String container() {
    String container = System.getenv("CONTAINER_NAME");
    return container == null ? "" : container;
  }

  public static String namespace() {
    String namespace = System.getenv("POD_NAMESPACE");
    return namespace == null || namespace.isBlank() ? "jafra-mart" : namespace;
  }
}
