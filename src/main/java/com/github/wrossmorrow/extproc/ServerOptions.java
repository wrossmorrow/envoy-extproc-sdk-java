package com.github.wrossmorrow.extproc;

/**
 * Options controlling the lifecycle of an {@code ExternalProcessor} gRPC server, aligned with the
 * Go and Rust SDKs' {@code ServerOptions}.
 *
 * <p>Currently honored: the extproc listen port, the Prometheus {@code /metrics} HTTP port, the
 * maximum number of concurrent processing streams, the unready propagation delay (time to wait
 * after marking the service not serving before initiating graceful shutdown), and the termination
 * grace period (bound on graceful shutdown, with force-stop fallback).
 */
public class ServerOptions {
  public int extProcPort = 50051;
  public int metricsPort = 9090;
  public int maxConcurrentStreams = 1000;
  public int unreadyPropagationDelaySeconds = 5;
  public int terminationGracePeriodSeconds = 10;

  public ServerOptions() {}

  /** Returns the default server options, matching the Go and Rust SDKs. */
  public static ServerOptions defaultOptions() {
    return new ServerOptions();
  }

  @Override
  public String toString() {
    return getClass().getName()
        + "@"
        + Integer.toHexString(hashCode())
        + ": "
        + "extProcPort("
        + extProcPort
        + ")"
        + " metricsPort("
        + metricsPort
        + ")"
        + " maxConcurrentStreams("
        + maxConcurrentStreams
        + ")"
        + " unreadyPropagationDelaySeconds("
        + unreadyPropagationDelaySeconds
        + ")"
        + " terminationGracePeriodSeconds("
        + terminationGracePeriodSeconds
        + ")";
  }
}
