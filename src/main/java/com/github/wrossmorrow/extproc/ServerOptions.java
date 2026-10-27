package com.github.wrossmorrow.extproc;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.DeserializationFeature;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Paths;

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
  @JsonProperty("extproc_port")
  public int extProcPort = 50051;

  @JsonProperty("metrics_http_port")
  public int metricsPort = 9090;

  @JsonProperty("max_concurrent_streams")
  public int maxConcurrentStreams = 1000;

  @JsonProperty("unready_propagation_delay_seconds")
  public int unreadyPropagationDelaySeconds = 5;

  @JsonProperty("termination_grace_period_seconds")
  public int terminationGracePeriodSeconds = 10;

  public ServerOptions() {}

  /** Returns the default server options, matching the Go and Rust SDKs. */
  public static ServerOptions defaultOptions() {
    return new ServerOptions();
  }

  /**
   * Loads server options from a JSON file, e.g. {@code {"extproc_port": 50051, "metrics_http_port":
   * 9090, "max_concurrent_streams": 1000, "unready_propagation_delay_seconds": 5,
   * "termination_grace_period_seconds": 10}}.
   */
  public static ServerOptions fromJson(String filePath) throws IOException {
    ObjectMapper mapper = new ObjectMapper();
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
      return mapper.readValue(in, ServerOptions.class);
    }
  }

  /**
   * Loads server options from a YAML file, e.g. {@code extproc_port: 50051 metrics_http_port: 9090
   * max_concurrent_streams: 1000 unready_propagation_delay_seconds: 5
   * termination_grace_period_seconds: 10}.
   */
  public static ServerOptions fromYaml(String filePath) throws IOException {
    ObjectMapper mapper = new ObjectMapper(new YAMLFactory());
    mapper.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, false);
    try (InputStream in = Files.newInputStream(Paths.get(filePath))) {
      return mapper.readValue(in, ServerOptions.class);
    }
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
