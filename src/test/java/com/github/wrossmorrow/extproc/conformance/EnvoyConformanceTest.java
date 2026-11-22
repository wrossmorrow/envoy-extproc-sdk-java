package com.github.wrossmorrow.extproc.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import com.github.wrossmorrow.extproc.ExternalProcessorServer;
import com.github.wrossmorrow.extproc.RequestContext;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.Arrays;
import java.util.HashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;

public class EnvoyConformanceTest {

  @BeforeAll
  static void loadManifest() {
    assertNotNull(EnvoyVersions.load().defaultVersion);
  }

  @Test
  void envoyVersionManifestIsLoaded() {
    EnvoyVersions.EnvoyVersionManifest manifest = EnvoyVersions.load();
    assertNotNull(manifest.defaultVersion);
    assertTrue(manifest.supported.contains("v1.37-latest"));
    assertTrue(manifest.supported.contains("v1.38-latest"));
    assertTrue(manifest.supported.contains("v1.39-latest"));
    assertTrue(manifest.supported.contains("v1.40-latest"));
    assertTrue(manifest.canary.contains("dev"));
  }

  @Test
  void envoyVersionSyncFromEndOfLifeComputesSupported() {
    byte[] eolJson =
        ("[{\"cycle\": \"v1.35\", \"eol\": \"2024-10-23\"}, {\"cycle\": \"v1.36\", \"eol\": null},"
                + " {\"cycle\": \"v1.37\", \"eol\": \"2025-02-06\"}]")
            .getBytes();
    EnvoyVersions.EnvoyVersionManifest manifest =
        EnvoyVersions.syncFromEol(eolJson, java.time.Instant.now(), Arrays.asList("dev"));
    assertEquals(1, manifest.supported.size());
    assertEquals("v1.36-latest", manifest.supported.get(0));
  }

  @Test
  void bootstrapBuildsAndSerializes() {
    EnvoyBootstrap.BootstrapParams params = new EnvoyBootstrap.BootstrapParams();
    params.upstreamHost = "127.0.0.1";
    params.primaryUpstreamPort = 18001;
    params.secondaryUpstreamPort = 18002;
    params.extProcPort = 50051;
    params.abortExtProcPort = 50052;
    params.secondaryExtProcPort = 50053;
    params.listenerPort = 18080;
    params.adminPort = 19901;

    String json = EnvoyBootstrap.bootstrapJson(params);
    assertNotNull(json);
    assertTrue(json.contains("envoy.filters.http.ext_proc"), json);
    assertTrue(json.contains("extproc_service"), json);
    assertTrue(json.contains("upstream_primary"), json);
    assertTrue(json.contains("conformance_listener"), json);
  }

  @Test
  void upstreamServerEchoesHttp1Requests() throws Exception {
    UpstreamServer upstream = UpstreamServer.start("primary");
    try {
      HttpURLConnection conn =
          (HttpURLConnection) new URL(upstream.url() + "/v1/foo?a=b").openConnection();
      conn.setRequestMethod("POST");
      conn.setDoOutput(true);
      conn.setRequestProperty("Content-Type", "text/plain");
      conn.setRequestProperty("x-conformance-proto", "HTTP/1.1");
      conn.setRequestProperty("x-conformance-host", "127.0.0.1:18080");
      conn.setRequestProperty("x-conformance-method", "GET");
      conn.setRequestProperty("x-conformance-path", "/v1/foo");
      conn.setRequestProperty("x-conformance-query", "a=b");
      conn.setRequestProperty("x-conformance-body", "hello");
      java.io.OutputStream out = conn.getOutputStream();
      out.write("hello".getBytes(StandardCharsets.UTF_8));
      out.close();
      int code = conn.getResponseCode();
      InputStream in = code < 400 ? conn.getInputStream() : conn.getErrorStream();
      ByteArrayOutputStream bos = new ByteArrayOutputStream();
      byte[] buf = new byte[8192];
      int n;
      while ((n = in.read(buf)) != -1) {
        bos.write(buf, 0, n);
      }
      String response = new String(bos.toByteArray(), StandardCharsets.UTF_8);
      assertEquals(200, code);
      assertNotNull(response);
      assertTrue(response.contains("server"), response);
      assertTrue(response.contains("method"), response);
    } finally {
      upstream.close();
    }
  }

  @Test
  void conformanceProcessorDispatchesByCaseHeader() {
    ConformanceProcessor processor = ConformanceProcessor.newDefault("conformance");
    final AtomicInteger headersCalls = new AtomicInteger();
    final AtomicInteger bodyCalls = new AtomicInteger();
    final AtomicInteger errorCalls = new AtomicInteger();

    processor.register(
        "headers_mutations_http1",
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            headersCalls.incrementAndGet();
          }

          @Override
          public void errorHandler(RequestContext ctx, RequestCase phase, Throwable error) {
            errorCalls.incrementAndGet();
          }
        });
    processor.register(
        "post_with_body_mode_none",
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            bodyCalls.incrementAndGet();
          }
        });

    RequestContext ctx = new RequestContext();
    Map<String, String> headers = new HashMap<String, String>();
    headers.put("x-conformance-case", "headers_mutations_http1");
    processor.processRequestHeaders(ctx, headers);
    assertEquals(1, headersCalls.get());

    ctx.setValue(ConformanceProcessor.CASE_HEADER, "post_with_body_mode_none");
    processor.processRequestBody(ctx, "hello");
    assertEquals(1, bodyCalls.get());
    assertEquals(0, errorCalls.get());
  }

  @Test
  @EnabledIfEnvironmentVariable(named = "CONFORMANCE_REQUIRE_ENVOY", matches = "1")
  void envoyGatedConformanceExchange() throws Exception {
    UpstreamServer upstream = UpstreamServer.start("primary");
    EnvoyRunner.EnvoyConfig config = new EnvoyRunner.EnvoyConfig();
    config.upstreamHost = "127.0.0.1";
    config.primaryUpstreamPort = upstream.port();
    config.secondaryUpstreamPort = upstream.port();
    config.extProcPort = 50052;
    config.abortExtProcPort = 50053;
    config.secondaryExtProcPort = 50054;
    ConformanceProcessor processor = ConformanceProcessor.newDefault("conformance");
    processor.register(
        "headers_mutations_http1",
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-extproc-req", "applied");
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-extproc-resp", "applied");
          }
        });
    ExternalProcessorServer extProcServer =
        new ExternalProcessorServer()
            .builder(config.extProcPort)
            .setMetricsPort(0)
            .setTerminationGracePeriodSeconds(2)
            .addRequestProcessor(processor);
    extProcServer.start();
    EnvoyRunner runner = EnvoyRunner.start(config);
    try {
      EnvoyHttpClient client = new EnvoyHttpClient(runner.proxyUrl(), 5000);
      Map<String, String> headers = new HashMap<String, String>();
      headers.put(ConformanceProcessor.CASE_HEADER, "headers_mutations_http1");
      EnvoyHttpClient.Response response = client.send("POST", "/v1/foo", headers, "hello");
      assertNotNull(response);
      assertEquals(200, response.status, response.body);
      assertTrue(
          response.body.toLowerCase(java.util.Locale.ROOT).contains("x-extproc-req"),
          response.body);
      assertEquals("applied", response.headers.get("x-extproc-resp"));
      runner.assertNoProtocolErrors();
      System.out.println("envoy conformance exchange status=" + response.status);
    } finally {
      try {
        extProcServer.stop();
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
      upstream.close();
      runner.close();
    }
  }

  @Test
  void dockerComposeAndDocsMatchDefaultEnvoyVersion() throws Exception {
    EnvoyVersions.EnvoyVersionManifest manifest = EnvoyVersions.load();
    String defaultVersion = manifest.defaultVersion;
    assertNotNull(defaultVersion);

    String compose =
        new String(Files.readAllBytes(Paths.get("docker-compose.yaml")), StandardCharsets.UTF_8);
    assertTrue(
        compose.contains("envoyproxy/envoy:${ENVOY_VERSION:-" + defaultVersion + "}"), compose);

    String conformanceDoc =
        new String(Files.readAllBytes(Paths.get("CONFORMANCE.md")), StandardCharsets.UTF_8);
    assertTrue(
        conformanceDoc.contains("envoyproxy/envoy:${ENVOY_VERSION:-" + defaultVersion + "}"),
        "CONFORMANCE.md Compose image default out of sync");
    assertTrue(
        conformanceDoc.contains("ENVOY_VERSION=" + defaultVersion),
        "CONFORMANCE.md ENVOY_VERSION example out of sync");

    String justfile = new String(Files.readAllBytes(Paths.get("justfile")), StandardCharsets.UTF_8);
    assertTrue(
        justfile.contains("conformance-version version=\"" + defaultVersion + "\""),
        "justfile conformance-version default out of sync");
  }

  @Test
  void syncHelpersRewriteComposeDocsAndJustfile() {
    EnvoyVersions.EnvoyVersionManifest manifest = new EnvoyVersions.EnvoyVersionManifest();
    manifest.defaultVersion = "v1.40-latest";
    manifest.supported =
        Arrays.asList("v1.37-latest", "v1.38-latest", "v1.39-latest", "v1.40-latest");

    String oldCompose = "  envoy:\n    image: envoyproxy/envoy:${ENVOY_VERSION:-v1.26-latest}\n";
    String updatedCompose = EnvoyVersions.syncDockerComposeYaml(oldCompose, manifest);
    assertEquals(
        "  envoy:\n    image: envoyproxy/envoy:${ENVOY_VERSION:-v1.40-latest}\n", updatedCompose);
    assertEquals(updatedCompose, EnvoyVersions.syncDockerComposeYaml(updatedCompose, manifest));

    String oldDoc =
        "| Routine CI Envoy | `envoyproxy/envoy:${ENVOY_VERSION:-v1.26-latest}` | note |\n"
            + "| Multi-Version Matrix | `v1.35` – `v1.38` + `dev` | notes |\n"
            + "Run (defaults to `ENVOY_VERSION=v1.26-latest`):\n";
    String updatedDoc = EnvoyVersions.syncConformanceDoc(oldDoc, manifest);
    assertEquals(
        "| Routine CI Envoy | `envoyproxy/envoy:${ENVOY_VERSION:-v1.40-latest}` | note |\n"
            + "| Multi-Version Matrix | `v1.37` – `v1.40` + `dev` | notes |\n"
            + "Run (defaults to `ENVOY_VERSION=v1.40-latest`):\n",
        updatedDoc);
    assertEquals(updatedDoc, EnvoyVersions.syncConformanceDoc(updatedDoc, manifest));

    String oldJust = "conformance-version version=\"v1.26-latest\" *flags:\n";
    String updatedJust = EnvoyVersions.syncJustfile(oldJust, manifest);
    assertEquals("conformance-version version=\"v1.40-latest\" *flags:\n", updatedJust);
    assertEquals(updatedJust, EnvoyVersions.syncJustfile(updatedJust, manifest));
  }
}
