package com.github.wrossmorrow.extproc.conformance;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Manages a live Envoy proxy instance (Docker or a local binary) for conformance testing, and
 * asserts on Envoy's ext_proc admin stats.
 */
public class EnvoyRunner {

  public static final class EnvoyConfig {
    public String version = "v1.40-latest";
    public String upstreamHost = "127.0.0.1";
    public int primaryUpstreamPort = 18001;
    public int secondaryUpstreamPort = 18002;
    public int extProcPort = 50051;
    public int abortExtProcPort = 50052;
    public int secondaryExtProcPort = 50053;
  }

  private static final ExecutorService EXECUTORS =
      Executors.newSingleThreadExecutor(
          r -> {
            Thread t = new Thread(r, "envoy-conformance-runner");
            t.setDaemon(true);
            return t;
          });

  private final String version;
  private final EnvoyConfig config;
  private volatile boolean running = true;
  private volatile Process process;
  private final AtomicBoolean dockerMode = new AtomicBoolean(false);
  private String proxyAddr;
  private String adminAddr;
  private final Map<String, Long> extProcStats = new HashMap<String, Long>();
  private String containerName;

  private EnvoyRunner(String version, EnvoyConfig config) {
    this.version = version;
    this.config = config;
  }

  public static EnvoyRunner start(EnvoyConfig config) {
    if (config == null) {
      throw new IllegalArgumentException("config is required");
    }
    String version =
        config.version != null && !config.version.isEmpty()
            ? config.version
            : EnvoyVersions.resolve();
    EnvoyConfig resolved = new EnvoyConfig();
    resolved.version = version;
    resolved.upstreamHost = config.upstreamHost;
    resolved.primaryUpstreamPort = config.primaryUpstreamPort;
    resolved.secondaryUpstreamPort = config.secondaryUpstreamPort;
    resolved.extProcPort = config.extProcPort;
    resolved.abortExtProcPort = config.abortExtProcPort;
    resolved.secondaryExtProcPort = config.secondaryExtProcPort;
    EnvoyRunner runner = new EnvoyRunner(version, resolved);
    try {
      runner.startInternal();
      return runner;
    } catch (Exception e) {
      runner.closeQuietly();
      throw new IllegalStateException("failed to start envoy " + version, e);
    }
  }

  private void startInternal() throws Exception {
    EnvoyBootstrap.BootstrapParams params = new EnvoyBootstrap.BootstrapParams();
    params.upstreamHost = config.upstreamHost;
    params.primaryUpstreamPort = config.primaryUpstreamPort;
    params.secondaryUpstreamPort = config.secondaryUpstreamPort;
    params.extProcPort = config.extProcPort;
    params.abortExtProcPort = config.abortExtProcPort;
    params.secondaryExtProcPort = config.secondaryExtProcPort;
    params.listenerPort = 18080;
    params.adminPort = 19901;

    String bootstrapJson = EnvoyBootstrap.bootstrapJson(params);
    String bootstrapPath = writeTempConfig(bootstrapJson);

    if (isEnvoyBinary()) {
      startBinaryEnvoy(bootstrapPath);
    } else {
      startDockerEnvoy(bootstrapPath);
    }
  }

  private boolean isEnvoyBinary() {
    String envoyBin = System.getenv("ENVOY_BIN");
    return envoyBin != null && !envoyBin.isEmpty();
  }

  private String writeTempConfig(String bootstrapJson) throws Exception {
    java.io.File file = java.io.File.createTempFile("envoy-conformance", ".json");
    java.io.FileOutputStream out = new java.io.FileOutputStream(file);
    out.write(bootstrapJson.getBytes(StandardCharsets.UTF_8));
    out.close();
    file.deleteOnExit();
    return file.getAbsolutePath();
  }

  private void startDockerEnvoy(String bootstrapPath) throws Exception {
    checkDockerAvailable();
    containerName = "extproc-conformance-" + System.nanoTime();
    String image = "envoyproxy/envoy:" + version;
    ProcessBuilder pb =
        new ProcessBuilder(
            "docker",
            "run",
            "-d",
            "--rm",
            "--name",
            containerName,
            "--add-host",
            "host.docker.internal:host-gateway",
            "-p",
            "127.0.0.1::18080",
            "-p",
            "127.0.0.1::19901",
            image,
            "envoy",
            "--config-yaml",
            bootstrapPath,
            "--log-level",
            "warn",
            "--concurrency",
            "2");
    pb.redirectErrorStream(true);
    Process docker = pb.start();
    dockerMode.set(true);
    dockerProcesses.put(containerName, docker);
    docker.waitFor(30, TimeUnit.SECONDS);
    String hostPort = readDockerPort(containerName, 18080);
    String adminPort = readDockerPort(containerName, 19901);
    proxyAddr = "127.0.0.1:" + hostPort;
    adminAddr = "127.0.0.1:" + adminPort;
    waitReady(adminAddr, 120);
  }

  private final Map<String, Process> dockerProcesses = new ConcurrentHashMap<String, Process>();

  private void startBinaryEnvoy(String bootstrapPath) throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder(
            System.getenv("ENVOY_BIN"),
            "--config-yaml",
            bootstrapPath,
            "--log-level",
            "warn",
            "--concurrency",
            "2");
    pb.redirectErrorStream(true);
    Process started = pb.start();
    process = started;
    started.waitFor(30, TimeUnit.SECONDS);
    proxyAddr = "127.0.0.1:18080";
    adminAddr = "127.0.0.1:19901";
    waitReady(adminAddr, 90);
  }

  private void checkDockerAvailable() throws Exception {
    ProcessBuilder pb = new ProcessBuilder("docker", "info", "--format", "{{.ServerVersion}}");
    pb.redirectErrorStream(true);
    Process p = pb.start();
    readProcessOutput(p);
    p.waitFor();
    if (p.exitValue() != 0) {
      throw new IllegalStateException("docker unavailable: " + readProcessOutput(p).trim());
    }
  }

  private static String readProcessOutput(Process p) throws Exception {
    java.io.InputStream in = p.getInputStream();
    ByteArrayOutputStream out = new ByteArrayOutputStream();
    byte[] buf = new byte[8192];
    int n;
    while ((n = in.read(buf)) != -1) {
      out.write(buf, 0, n);
    }
    p.getOutputStream().close();
    return new String(out.toByteArray(), StandardCharsets.UTF_8);
  }

  private String readDockerPort(String containerName, int containerPort) throws Exception {
    ProcessBuilder pb =
        new ProcessBuilder("docker", "port", containerName, String.valueOf(containerPort) + "/tcp");
    pb.redirectErrorStream(true);
    Process p = pb.start();
    String out = readProcessOutput(p);
    p.waitFor();
    String line = out.trim().split("\n")[0].trim();
    int idx = line.lastIndexOf(':');
    if (idx < 0) {
      throw new IllegalStateException("cannot parse docker port output: " + line);
    }
    return line.substring(idx + 1);
  }

  private void waitReady(String adminAddr, int timeoutSeconds) throws Exception {
    long deadline = System.currentTimeMillis() + timeoutSeconds * 1000L;
    while (System.currentTimeMillis() < deadline) {
      int code = getStatus(adminAddr, "/ready");
      if (code == 200) {
        return;
      }
      Thread.sleep(200);
    }
    throw new IllegalStateException("timed out waiting for envoy admin /ready at " + adminAddr);
  }

  private static int getStatus(String baseUrl, String path) {
    try {
      URL url = new URL(baseUrl + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.setRequestMethod("GET");
      int code = conn.getResponseCode();
      conn.disconnect();
      return code;
    } catch (Exception e) {
      return -1;
    }
  }

  private String get(String baseUrl, String path) {
    try {
      URL url = new URL(baseUrl + path);
      HttpURLConnection conn = (HttpURLConnection) url.openConnection();
      conn.setConnectTimeout(2000);
      conn.setReadTimeout(2000);
      conn.setRequestMethod("GET");
      int code = conn.getResponseCode();
      InputStream in =
          code < 0 ? null : (code < 400 ? conn.getInputStream() : conn.getErrorStream());
      java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      if (in != null) {
        while ((n = in.read(buf)) != -1) {
          out.write(buf, 0, n);
        }
      }
      conn.disconnect();
      if (code >= 400) {
        throw new IllegalStateException("envoy admin GET " + path + " failed with status " + code);
      }
      return new String(out.toByteArray(), StandardCharsets.UTF_8);
    } catch (Exception e) {
      throw new IllegalStateException("envoy admin GET " + path + " failed", e);
    }
  }

  public String proxyAddr() {
    return proxyAddr;
  }

  public String proxyUrl() {
    return "http://" + proxyAddr;
  }

  public String adminAddr() {
    return adminAddr;
  }

  public Map<String, Long> extProcStats() {
    String body = get(adminAddr, "/stats?filter=ext_proc&format=json");
    Map<String, Long> stats = new HashMap<String, Long>();
    try {
      JsonNode root = new ObjectMapper().readTree(body);
      JsonNode statsArray = root.get("stats");
      if (statsArray != null && statsArray.isArray()) {
        for (JsonNode node : statsArray) {
          if (node.has("name") && node.has("value")) {
            stats.put(node.get("name").asText(), node.get("value").asLong());
          }
        }
      }
    } catch (Exception e) {
      throw new IllegalStateException("cannot parse envoy ext_proc stats", e);
    }
    return stats;
  }

  public void assertNoProtocolErrors() {
    Map<String, Long> stats = extProcStats();
    for (Map.Entry<String, Long> entry : stats.entrySet()) {
      if (entry.getKey() != null && entry.getKey().endsWith(".spurious_msgs_received")) {
        throw new IllegalStateException("envoy reported spurious messages for " + entry.getKey());
      }
    }
  }

  public void close() {
    if (dockerMode.get()) {
      if (containerName != null) {
        try {
          ProcessBuilder pb = new ProcessBuilder("docker", "rm", "-f", containerName);
          pb.redirectErrorStream(true);
          Process p = pb.start();
          readProcessOutput(p);
          p.waitFor();
        } catch (Exception ignored) {
        }
      }
      return;
    }
    Process p = process;
    if (p != null) {
      try {
        p.destroy();
        p.waitFor(10, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }

  private void closeQuietly() {
    if (dockerMode.get()) {
      if (containerName != null) {
        try {
          ProcessBuilder pb = new ProcessBuilder("docker", "rm", "-f", containerName);
          pb.redirectErrorStream(true);
          Process p = pb.start();
          readProcessOutput(p);
          p.waitFor();
        } catch (Exception ignored) {
        }
      }
      return;
    }
    Process p = process;
    if (p != null) {
      try {
        p.destroy();
        p.waitFor(10, TimeUnit.SECONDS);
      } catch (Exception ignored) {
      }
    }
  }
}
