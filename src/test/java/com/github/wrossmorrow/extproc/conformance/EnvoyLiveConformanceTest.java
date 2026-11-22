package com.github.wrossmorrow.extproc.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.ExternalProcessorServer;
import com.github.wrossmorrow.extproc.ModeOverride;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.ProtocolConfig;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.ServerOptions;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestInfo;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.junit.jupiter.api.extension.ExtensionContext;
import org.junit.jupiter.api.extension.RegisterExtension;
import org.junit.jupiter.api.extension.TestWatcher;

/**
 * Live multi-version Envoy conformance suite exercising the SDK against a real Envoy instance
 * (Docker or {@code ENVOY_BIN}) across HTTP/1.1, h2c, processing modes, trailers, routing,
 * immediate responses, timeouts, cancellations, error policies, and filter chaining.
 */
@EnabledIfEnvironmentVariable(named = "CONFORMANCE_REQUIRE_ENVOY", matches = "1")
public class EnvoyLiveConformanceTest {

  private static final ObjectMapper MAPPER = new ObjectMapper();
  private static final Map<String, String> OUTCOMES =
      Collections.synchronizedMap(new LinkedHashMap<String, String>());

  @RegisterExtension
  static final TestWatcher OUTCOME_WATCHER =
      new TestWatcher() {
        @Override
        public void testSuccessful(ExtensionContext context) {
          record(context, "pass");
        }

        @Override
        public void testFailed(ExtensionContext context, Throwable cause) {
          record(context, "fail");
        }

        @Override
        public void testAborted(ExtensionContext context, Throwable cause) {
          record(context, "skip");
        }

        private void record(ExtensionContext context, String status) {
          String method = context.getRequiredTestMethod().getName();
          OUTCOMES.put("TestEnvoyConformance/" + method, status);
        }
      };

  private static UpstreamServer primaryUpstream;
  private static UpstreamServer secondaryUpstream;
  private static ConformanceProcessor defaultProcessor;
  private static ConformanceProcessor abortProcessor;
  private static ConformanceProcessor secondaryProcessor;
  private static ExternalProcessorServer defaultServer;
  private static ExternalProcessorServer abortServer;
  private static ExternalProcessorServer secondaryServer;
  private static EnvoyRunner runner;
  private static EnvoyHttpClient client;
  private static String envoyVersion;

  @BeforeAll
  static void setUpCluster() throws Exception {
    envoyVersion = EnvoyVersions.resolve();
    primaryUpstream = UpstreamServer.start("primary");
    secondaryUpstream = UpstreamServer.start("secondary");

    defaultProcessor = ConformanceProcessor.newDefault("conformance-default");
    ProcessingOptions abortOpts = new ProcessingOptions();
    abortOpts.abortOnProcessorFailure = true;
    abortProcessor = new ConformanceProcessor("conformance-abort", abortOpts);
    secondaryProcessor = ConformanceProcessor.newDefault("conformance-secondary");

    int defaultPort = freePort();
    int abortPort = freePort();
    int secondaryPort = freePort();

    defaultServer = startExtProcServer(defaultProcessor, defaultPort);
    abortServer = startExtProcServer(abortProcessor, abortPort);
    secondaryServer = startExtProcServer(secondaryProcessor, secondaryPort);

    EnvoyRunner.EnvoyConfig config = new EnvoyRunner.EnvoyConfig();
    config.version = envoyVersion;
    config.upstreamHost = "127.0.0.1";
    config.primaryUpstreamPort = primaryUpstream.port();
    config.secondaryUpstreamPort = secondaryUpstream.port();
    config.extProcPort = defaultPort;
    config.abortExtProcPort = abortPort;
    config.secondaryExtProcPort = secondaryPort;
    runner = EnvoyRunner.start(config);
    client = new EnvoyHttpClient(runner.proxyUrl(), 5000);
  }

  private static int freePort() throws Exception {
    try (ServerSocket socket = new ServerSocket(0)) {
      return socket.getLocalPort();
    }
  }

  private static ExternalProcessorServer startExtProcServer(
      ConformanceProcessor processor, int port) throws Exception {
    ServerOptions opts = ServerOptions.defaultOptions();
    opts.extProcPort = port;
    opts.metricsPort = 0;
    opts.unreadyPropagationDelaySeconds = 0;
    opts.terminationGracePeriodSeconds = 2;
    return new ExternalProcessorServer().builder(opts).addRequestProcessor(processor).start();
  }

  @AfterAll
  static void tearDownCluster() throws Exception {
    try {
      if (runner != null) {
        runner.assertNoProtocolErrors();
      }
    } finally {
      if (runner != null) {
        runner.close();
      }
      if (defaultServer != null) {
        defaultServer.stop();
      }
      if (abortServer != null) {
        abortServer.stop();
      }
      if (secondaryServer != null) {
        secondaryServer.stop();
      }
      if (primaryUpstream != null) {
        primaryUpstream.close();
      }
      if (secondaryUpstream != null) {
        secondaryUpstream.close();
      }
      writeOutcomesFile();
    }
  }

  private static void writeOutcomesFile() {
    if (OUTCOMES.isEmpty() || envoyVersion == null) {
      return;
    }
    try {
      Path dir = Paths.get("conformance", "results");
      Files.createDirectories(dir);
      ConformanceReport.VersionTestRun run = new ConformanceReport.VersionTestRun();
      run.version = envoyVersion;
      run.outcomes.putAll(OUTCOMES);
      ConformanceReport.VersionResult result = new ConformanceReport.VersionResult();
      result.version = envoyVersion;
      result.run = run;
      Path out = dir.resolve("results-" + envoyVersion + ".json");
      MAPPER.writerWithDefaultPrettyPrinter().writeValue(out.toFile(), result);
    } catch (Exception e) {
      System.err.println("Failed to write conformance results: " + e.getMessage());
    }
  }

  private static UpstreamServer.UpstreamEcho parseEcho(EnvoyHttpClient.Response resp)
      throws Exception {
    assertEquals(200, resp.status, "unexpected status " + resp.status + ": " + resp.body);
    return MAPPER.readValue(resp.body, UpstreamServer.UpstreamEcho.class);
  }

  private static Map<String, String> caseHeaders(String caseId) {
    Map<String, String> h = new LinkedHashMap<String, String>();
    h.put(ConformanceProcessor.CASE_HEADER, caseId);
    return h;
  }

  @Test
  void headers_mutations_http1(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.addHeader("x-req-add", "added");
            ctx.overwriteHeader("x-req-overwrite", "after");
            ctx.removeHeader("x-req-remove");
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.addHeader("x-resp-add", "resp-added");
            ctx.overwriteHeader("x-resp-overwrite", "resp-after");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-req-overwrite", "before");
    headers.put("x-req-remove", "drop-me");
    EnvoyHttpClient.Response resp = client.send("POST", "/mode/buffered", headers, "hi");
    UpstreamServer.UpstreamEcho echo = parseEcho(resp);
    assertEquals(Collections.singletonList("added"), echo.headers.get("x-req-add"));
    assertEquals(Collections.singletonList("after"), echo.headers.get("x-req-overwrite"));
    assertFalse(echo.headers.containsKey("x-req-remove"));
    assertEquals("resp-added", resp.headers.get("x-resp-add"));
    assertEquals("resp-after", resp.headers.get("x-resp-overwrite"));
  }

  @Test
  void headers_mutations_h2c(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-h2c-req", "ok");
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-h2c-resp", "ok");
          }
        });
    EnvoyHttpClient.Response resp =
        client.sendH2c("POST", "/mode/buffered", caseHeaders(id), "h2c-body");
    UpstreamServer.UpstreamEcho echo = parseEcho(resp);
    assertEquals("HTTP/2.0", echo.proto);
    assertEquals(Collections.singletonList("ok"), echo.headers.get("x-h2c-req"));
    assertEquals("ok", resp.headers.get("x-h2c-resp"));
  }

  @Test
  void headers_multivalue_and_context_state(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.setValue("carried", ctx.getMethod() + ":" + ctx.getPath());
            ctx.setValue(
                "comma-count",
                String.valueOf(ctx.getAllRequestHeaders().splitList("x-csv").size()));
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-carried", String.valueOf(ctx.getValue("carried")));
            ctx.overwriteHeader("x-comma-count", String.valueOf(ctx.getValue("comma-count")));
            ctx.appendHeader("set-cookie", "a=1");
            ctx.appendHeader("set-cookie", "b=2");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-csv", "one,two,three");
    EnvoyHttpClient.Response resp = client.send("GET", "/mode/none?q=1", headers, null);
    assertEquals(200, resp.status, resp.body);
    assertEquals("GET:/mode/none", resp.headers.get("x-carried"));
    assertEquals("3", resp.headers.get("x-comma-count"));
    List<String> cookies = resp.allHeaders.get("set-cookie");
    assertNotNull(cookies);
    assertEquals(2, cookies.size());
    assertTrue(cookies.containsAll(Arrays.asList("a=1", "b=2")), cookies.toString());
  }

  @Test
  void skip_request_headers_response_mutation(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final AtomicInteger reqHeaderCalls = new AtomicInteger();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            reqHeaderCalls.incrementAndGet();
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-skip-req-resp", "yes");
          }
        });
    EnvoyHttpClient.Response resp =
        client.send("GET", "/mode/skip-req-headers", caseHeaders(id), null);
    assertEquals(200, resp.status, resp.body);
    assertEquals(0, reqHeaderCalls.get());
    assertEquals("yes", resp.headers.get("x-skip-req-resp"));
  }

  @Test
  void header_append_action_envoy_gap(TestInfo info) throws Exception {
    String strict = System.getenv("CONFORMANCE_STRICT_APPEND_ACTION");
    Assumptions.assumeTrue("1".equals(strict), "CONFORMANCE_STRICT_APPEND_ACTION!=1");
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.addHeader("x-existing", "should-not-overwrite");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-existing", "original");
    UpstreamServer.UpstreamEcho echo = parseEcho(client.send("GET", "/mode/none", headers, null));
    assertEquals(Collections.singletonList("original"), echo.headers.get("x-existing"));
  }

  @Test
  void get_nobody_mode_none(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final AtomicInteger bodyCalls = new AtomicInteger();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            bodyCalls.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("GET", "/mode/none", caseHeaders(id), null));
    assertEquals("GET", echo.method);
    assertEquals(0, echo.bodyBytes);
    assertEquals(0, bodyCalls.get());
  }

  @Test
  void post_with_body_mode_none(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final AtomicInteger bodyCalls = new AtomicInteger();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            bodyCalls.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("POST", "/mode/none", caseHeaders(id), "pass-through"));
    assertEquals("pass-through", echo.body);
    assertEquals(0, bodyCalls.get());
  }

  @Test
  void buffered_body_replace_grow_and_shrink(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            if ("grow".equals(body)) {
              ctx.replaceBody("much-longer-replaced-request-body");
            } else {
              ctx.replaceBody("tiny");
            }
          }
        });
    UpstreamServer.UpstreamEcho grown =
        parseEcho(client.send("POST", "/mode/buffered", caseHeaders(id), "grow"));
    assertEquals("much-longer-replaced-request-body", grown.body);
    UpstreamServer.UpstreamEcho shrunk =
        parseEcho(client.send("POST", "/mode/buffered", caseHeaders(id), "very-long-input-body"));
    assertEquals("tiny", shrunk.body);
  }

  @Test
  void buffered_body_clear(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            ctx.clearBody();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("POST", "/mode/buffered", caseHeaders(id), "to-be-cleared"));
    assertEquals("", echo.body);
    assertEquals(0, echo.bodyBytes);
  }

  @Test
  void http1_chunked_response_buffered_replace_and_clear(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void responseBody(RequestContext ctx, String body) {
            ctx.replaceBody("buffered-resp-replaced");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-upstream-chunks", "4");
    headers.put("x-upstream-raw-body", "chunk-one-chunk-two-chunk-three");
    EnvoyHttpClient.Response resp = client.send("GET", "/mode/buffered", headers, null);
    assertEquals(200, resp.status, resp.body);
    assertEquals("buffered-resp-replaced", resp.body);
  }

  @Test
  void streamed_body_chunks(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            ctx.replaceBodyChunk(body.toUpperCase(Locale.ROOT));
          }
        });
    EnvoyHttpClient.Response resp =
        client.sendChunked(
            "POST",
            "/mode/streamed",
            caseHeaders(id),
            Arrays.asList("hello-", "streamed-", "world"),
            null);
    UpstreamServer.UpstreamEcho echo = parseEcho(resp);
    assertEquals("HELLO-STREAMED-WORLD", echo.body);
  }

  @Test
  void streamed_request_and_response_clear_chunk(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            ctx.clearBodyChunk();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("POST", "/mode/streamed", caseHeaders(id), "drop-this"));
    assertEquals("", echo.body);
  }

  @Test
  void buffered_partial_mode(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final AtomicInteger calls = new AtomicInteger();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            calls.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho small =
        parseEcho(client.send("POST", "/mode/buffered-partial", caseHeaders(id), "small"));
    assertEquals("small", small.body);
    StringBuilder big = new StringBuilder();
    for (int i = 0; i < 200; i++) {
      big.append('a');
    }
    UpstreamServer.UpstreamEcho large =
        parseEcho(client.send("POST", "/mode/buffered-partial", caseHeaders(id), big.toString()));
    assertEquals(200, large.bodyBytes);
    assertTrue(calls.get() >= 2);
  }

  @Test
  void http1_trailers_roundtrip(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestTrailers(RequestContext ctx, Map<String, String> trailers) {
            ctx.overwriteHeader("x-req-trailer-added", "req-t");
          }

          @Override
          public void responseTrailers(RequestContext ctx, Map<String, String> trailers) {
            ctx.overwriteHeader("x-resp-trailer-added", "resp-t");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-upstream-trailer-x-up-trailer", "from-upstream");
    Map<String, String> reqTrailers = new LinkedHashMap<String, String>();
    reqTrailers.put("x-client-trailer", "from-client");
    EnvoyHttpClient.Response resp =
        client.sendChunked("POST", "/mode/trailers", headers, Arrays.asList("body"), reqTrailers);
    UpstreamServer.UpstreamEcho echo = parseEcho(resp);
    assertEquals(Collections.singletonList("from-client"), echo.trailers.get("x-client-trailer"));
    assertEquals(Collections.singletonList("req-t"), echo.trailers.get("x-req-trailer-added"));
    assertEquals("from-upstream", resp.trailers.get("x-up-trailer"));
    assertEquals("resp-t", resp.trailers.get("x-resp-trailer-added"));
  }

  @Test
  void h2c_trailers_roundtrip(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void responseTrailers(RequestContext ctx, Map<String, String> trailers) {
            ctx.overwriteHeader("x-h2c-trailer-seen", "yes");
          }
        });
    Map<String, String> headers = caseHeaders(id);
    headers.put("x-upstream-trailer-x-up-h2c", "val");
    EnvoyHttpClient.Response resp =
        client.sendH2c("POST", "/mode/trailers", headers, "h2c-trailers");
    assertEquals(200, resp.status, resp.body);
  }

  @Test
  void clear_route_cache_upstream_switch(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader(":authority", "upstream-secondary");
            ctx.clearRouteCache();
          }
        });
    UpstreamServer.UpstreamEcho echo = parseEcho(client.send("GET", "/", caseHeaders(id), null));
    assertEquals("secondary", echo.server);
  }

  @Test
  void clear_route_cache_from_request_body(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            ctx.clearRouteCache();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("POST", "/mode/buffered", caseHeaders(id), "body"));
    assertEquals("primary", echo.server);
  }

  @Test
  void immediate_response_request_headers(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            Map<String, String> respHeaders = new HashMap<String, String>();
            respHeaders.put("x-immediate", "from-headers");
            ctx.cancelRequest(403, respHeaders, "blocked");
          }
        });
    EnvoyHttpClient.Response resp = client.send("GET", "/mode/buffered", caseHeaders(id), null);
    assertEquals(403, resp.status);
    assertEquals("blocked", resp.body);
    assertEquals("from-headers", resp.headers.get("x-immediate"));
  }

  @Test
  void immediate_response_all_phases_and_trailers_fallback(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id + "-body",
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestBody(RequestContext ctx, String body) {
            ctx.cancelRequest(409, "cancelled-in-body");
          }
        });
    EnvoyHttpClient.Response bodyResp =
        client.send("POST", "/mode/buffered", caseHeaders(id + "-body"), "trigger");
    assertEquals(409, bodyResp.status);
    assertEquals("cancelled-in-body", bodyResp.body);

    defaultProcessor.register(
        id + "-resp-headers",
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.cancelRequest(429, "cancelled-in-resp-headers");
          }
        });
    EnvoyHttpClient.Response respHdrResp =
        client.send("GET", "/mode/buffered", caseHeaders(id + "-resp-headers"), null);
    assertEquals(429, respHdrResp.status);
    assertEquals("cancelled-in-resp-headers", respHdrResp.body);
  }

  @Test
  void per_route_disabled_bypass(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final AtomicInteger calls = new AtomicInteger();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            calls.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("GET", "/mode/disabled", caseHeaders(id), null));
    assertEquals("primary", echo.server);
    assertEquals(0, calls.get());
  }

  @Test
  void downstream_client_cancel_graceful(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    final CountDownLatch started = new CountDownLatch(1);
    final CountDownLatch errorSeen = new CountDownLatch(1);
    final AtomicBoolean wasCancelled = new AtomicBoolean(false);
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            started.countDown();
            try {
              Thread.sleep(600);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
          }

          @Override
          public void errorHandler(RequestContext ctx, RequestCase phase, Throwable error) {
            wasCancelled.set(true);
            errorSeen.countDown();
          }
        });
    URI uri = URI.create(runner.proxyUrl());
    try (Socket s = new Socket()) {
      s.connect(new InetSocketAddress(uri.getHost(), uri.getPort()), 2000);
      OutputStream out = s.getOutputStream();
      String req =
          "GET /mode/buffered HTTP/1.1\r\nHost: localhost\r\n"
              + ConformanceProcessor.CASE_HEADER
              + ": "
              + id
              + "\r\n\r\n";
      out.write(req.getBytes(StandardCharsets.US_ASCII));
      out.flush();
      assertTrue(started.await(3, TimeUnit.SECONDS));
    }
    assertTrue(errorSeen.await(3, TimeUnit.SECONDS));
    assertTrue(wasCancelled.get());
  }

  @Test
  void message_timeout_fail_closed_and_fail_open(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            try {
              Thread.sleep(350);
            } catch (InterruptedException e) {
              Thread.currentThread().interrupt();
            }
            ctx.overwriteHeader("x-should-timeout", "yes");
          }
        });
    EnvoyHttpClient.Response closedResp =
        client.send("GET", "/profile/timeout-fail-closed", caseHeaders(id), null);
    assertEquals(504, closedResp.status);

    EnvoyHttpClient.Response openResp =
        client.send("GET", "/profile/timeout-fail-open", caseHeaders(id), null);
    UpstreamServer.UpstreamEcho echo = parseEcho(openResp);
    assertFalse(echo.headers.containsKey("x-should-timeout"));
  }

  @Test
  void processor_error_continue_vs_abort(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            throw new RuntimeException("boom");
          }
        });
    abortProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            throw new RuntimeException("boom");
          }
        });

    UpstreamServer.UpstreamEcho continueEcho =
        parseEcho(client.send("GET", "/mode/none", caseHeaders(id), null));
    assertEquals("primary", continueEcho.server);

    EnvoyHttpClient.Response abortClosed =
        client.send("GET", "/profile/abort-fail-closed", caseHeaders(id), null);
    assertEquals(500, abortClosed.status);

    UpstreamServer.UpstreamEcho abortOpenEcho =
        parseEcho(client.send("GET", "/profile/abort-fail-open", caseHeaders(id), null));
    assertEquals("primary", abortOpenEcho.server);
  }

  @Test
  void multi_filter_chaining(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-chain-1", "first");
          }
        });
    secondaryProcessor.register(
        id,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overwriteHeader("x-chain-2", headers.get("x-chain-1") + "+second");
          }
        });
    UpstreamServer.UpstreamEcho echo =
        parseEcho(client.send("GET", "/profile/chained", caseHeaders(id), null));
    assertEquals(Collections.singletonList("first"), echo.headers.get("x-chain-1"));
    assertEquals(Collections.singletonList("first+second"), echo.headers.get("x-chain-2"));
  }

  @Test
  void observability_mode_envoy_gap(TestInfo info) throws Exception {
    String strict = System.getenv("CONFORMANCE_STRICT_OBSERVABILITY");
    Assumptions.assumeTrue("1".equals(strict), "CONFORMANCE_STRICT_OBSERVABILITY!=1");
    String id = info.getTestMethod().get().getName();
    defaultProcessor.register(id, new ConformanceProcessor.CaseHandler() {});
    EnvoyHttpClient.Response resp =
        client.send("GET", "/profile/observability", caseHeaders(id), null);
    assertEquals(200, resp.status);
    runner.assertNoProtocolErrors();
  }

  @Test
  void mode_override(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();

    // 1. opt_in_buffered_bodies_and_trailers
    String optInId = id + "-opt-in";
    final AtomicReference<String> reqBodySeen = new AtomicReference<String>("");
    final Map<String, String> reqTrailersSeen =
        Collections.synchronizedMap(new LinkedHashMap<String, String>());
    final AtomicBoolean respHeadersCalled = new AtomicBoolean(false);
    final AtomicReference<String> respBodySeen = new AtomicReference<String>("");
    final Map<String, String> respTrailersSeen =
        Collections.synchronizedMap(new LinkedHashMap<String, String>());
    defaultProcessor.register(
        optInId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMode(
                new ModeOverride()
                    .setResponseHeaderMode(ModeOverride.HeaderSendMode.SEND)
                    .setRequestBodyMode(ModeOverride.BodySendMode.BUFFERED)
                    .setResponseBodyMode(ModeOverride.BodySendMode.BUFFERED)
                    .setRequestTrailerMode(ModeOverride.HeaderSendMode.SEND)
                    .setResponseTrailerMode(ModeOverride.HeaderSendMode.SEND));
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            reqBodySeen.set(body);
            ctx.replaceBody("req-override:" + body);
          }

          @Override
          public void requestTrailers(RequestContext ctx, Map<String, String> trailers) {
            reqTrailersSeen.putAll(trailers);
            ctx.overwriteHeader("x-req-trailer-override", "from-extproc");
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            respHeadersCalled.set(true);
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            respBodySeen.set(body);
            ctx.replaceBody("resp-override:" + body);
          }

          @Override
          public void responseTrailers(RequestContext ctx, Map<String, String> trailers) {
            respTrailersSeen.putAll(trailers);
            ctx.overwriteHeader("x-resp-trailer-override", "from-extproc");
          }
        });
    Map<String, String> optInHeaders = caseHeaders(optInId);
    optInHeaders.put("x-upstream-trailer-x-up-trailer", "from-upstream");
    Map<String, String> optInTrailers = new LinkedHashMap<String, String>();
    optInTrailers.put("x-client-trailer", "from-client");
    EnvoyHttpClient.Response optInResp =
        client.sendH2c("POST", "/profile/mode-override", optInHeaders, "hello", optInTrailers);
    assertEquals(200, optInResp.status, optInResp.body);
    assertTrue(optInResp.body.startsWith("resp-override:"), optInResp.body);
    UpstreamServer.UpstreamEcho optInEcho =
        MAPPER.readValue(
            optInResp.body.substring("resp-override:".length()), UpstreamServer.UpstreamEcho.class);
    assertEquals("hello", reqBodySeen.get());
    assertEquals("req-override:hello", optInEcho.body);
    assertEquals("from-client", reqTrailersSeen.get("x-client-trailer"));
    assertEquals(
        Collections.singletonList("from-client"), optInEcho.trailers.get("x-client-trailer"));
    assertEquals(
        Collections.singletonList("from-extproc"),
        optInEcho.trailers.get("x-req-trailer-override"));
    assertTrue(respHeadersCalled.get());
    assertFalse(respBodySeen.get().isEmpty());
    assertEquals("from-upstream", respTrailersSeen.get("x-up-trailer"));

    // 2. skip_remaining_phases
    String skipId = id + "-skip";
    final AtomicInteger unexpectedPhases = new AtomicInteger();
    defaultProcessor.register(
        skipId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.skipRemainingPhases();
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            unexpectedPhases.incrementAndGet();
          }

          @Override
          public void requestTrailers(RequestContext ctx, Map<String, String> trailers) {
            unexpectedPhases.incrementAndGet();
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            unexpectedPhases.incrementAndGet();
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            unexpectedPhases.incrementAndGet();
          }

          @Override
          public void responseTrailers(RequestContext ctx, Map<String, String> trailers) {
            unexpectedPhases.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho skipEcho =
        parseEcho(client.send("POST", "/profile/mode-override", caseHeaders(skipId), "skip-body"));
    assertEquals("skip-body", skipEcho.body);
    assertEquals(0, unexpectedPhases.get());

    // 3. response_headers_streamed_body
    String streamId = id + "-resp-streamed";
    final AtomicInteger respStreamCalls = new AtomicInteger();
    defaultProcessor.register(
        streamId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMode(
                new ModeOverride()
                    .setResponseHeaderMode(ModeOverride.HeaderSendMode.SEND)
                    .setResponseBodyMode(ModeOverride.BodySendMode.STREAMED));
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            respStreamCalls.incrementAndGet();
            ctx.replaceBodyChunk(body.toUpperCase(Locale.ROOT));
          }
        });
    Map<String, String> streamHeaders = caseHeaders(streamId);
    streamHeaders.put("x-upstream-chunks", "3");
    streamHeaders.put("x-upstream-raw-body", "chunk1-chunk2-chunk3");
    EnvoyHttpClient.Response streamResp =
        client.send("GET", "/profile/mode-override", streamHeaders, null);
    assertEquals(200, streamResp.status, streamResp.body);
    assertTrue(respStreamCalls.get() >= 1);
    assertEquals("CHUNK1-CHUNK2-CHUNK3", streamResp.body);

    // 4. ignored_when_disabled_on_filter
    String disabledId = id + "-disabled-filter";
    final AtomicInteger disabledCalls = new AtomicInteger();
    defaultProcessor.register(
        disabledId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMode(
                new ModeOverride()
                    .setRequestBodyMode(ModeOverride.BodySendMode.BUFFERED)
                    .setResponseBodyMode(ModeOverride.BodySendMode.BUFFERED));
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            disabledCalls.incrementAndGet();
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            disabledCalls.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho disabledEcho =
        parseEcho(client.send("POST", "/mode/none", caseHeaders(disabledId), "unchanged"));
    assertEquals("unchanged", disabledEcho.body);
    assertEquals(0, disabledCalls.get());

    // 5. allowed_override_modes_allowlist
    String allowOkId = id + "-allowlist-ok";
    final AtomicInteger allowOkReqBody = new AtomicInteger();
    final AtomicInteger allowOkRespBody = new AtomicInteger();
    defaultProcessor.register(
        allowOkId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMode(
                new ModeOverride()
                    .setResponseHeaderMode(ModeOverride.HeaderSendMode.SEND)
                    .setRequestBodyMode(ModeOverride.BodySendMode.BUFFERED)
                    .setResponseBodyMode(ModeOverride.BodySendMode.NONE)
                    .setRequestTrailerMode(ModeOverride.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            allowOkReqBody.incrementAndGet();
            ctx.replaceBody("allowlisted:" + body);
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            allowOkRespBody.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho allowOkEcho =
        parseEcho(
            client.send(
                "POST", "/profile/mode-override-allowlist", caseHeaders(allowOkId), "payload"));
    assertEquals("allowlisted:payload", allowOkEcho.body);
    assertEquals(1, allowOkReqBody.get());
    assertEquals(0, allowOkRespBody.get());

    String allowDeniedId = id + "-allowlist-denied";
    final AtomicInteger allowDeniedReqBody = new AtomicInteger();
    defaultProcessor.register(
        allowDeniedId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMode(
                new ModeOverride()
                    .setResponseHeaderMode(ModeOverride.HeaderSendMode.SEND)
                    .setRequestBodyMode(ModeOverride.BodySendMode.STREAMED)
                    .setResponseBodyMode(ModeOverride.BodySendMode.NONE)
                    .setRequestTrailerMode(ModeOverride.HeaderSendMode.SKIP)
                    .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            allowDeniedReqBody.incrementAndGet();
          }
        });
    UpstreamServer.UpstreamEcho allowDeniedEcho =
        parseEcho(
            client.send(
                "POST", "/profile/mode-override-allowlist", caseHeaders(allowDeniedId), "payload"));
    assertEquals("payload", allowDeniedEcho.body);
    assertEquals(0, allowDeniedReqBody.get());
  }

  @Test
  void protocol_config(TestInfo info) throws Exception {
    String id = info.getTestMethod().get().getName();

    // 1. body_modes_and_cross_phase_persistence
    String[][] modeCases = {
      {"none", "/mode/none/protocol-config", "NONE", "NONE", "false"},
      {"buffered", "/mode/buffered/protocol-config", "BUFFERED", "BUFFERED", "true"},
      {"streamed", "/mode/streamed/protocol-config", "STREAMED", "STREAMED", "true"},
      {
        "buffered_partial",
        "/mode/buffered-partial/protocol-config",
        "BUFFERED_PARTIAL",
        "BUFFERED_PARTIAL",
        "true"
      }
    };
    for (String[] tc : modeCases) {
      String caseId = id + "-" + tc[0];
      ModeOverride.BodySendMode wantReq = ModeOverride.BodySendMode.valueOf(tc[2]);
      ModeOverride.BodySendMode wantResp = ModeOverride.BodySendMode.valueOf(tc[3]);
      boolean expectBodies = Boolean.parseBoolean(tc[4]);

      final Map<String, ProtocolConfig> observed =
          Collections.synchronizedMap(new LinkedHashMap<String, ProtocolConfig>());
      final List<String> nilPhases = Collections.synchronizedList(new ArrayList<String>());

      defaultProcessor.register(
          caseId,
          new ConformanceProcessor.CaseHandler() {
            private void record(String phaseName, RequestContext ctx) {
              ProtocolConfig pc = ctx.getProtocolConfig();
              if (pc == null) {
                nilPhases.add(phaseName);
              } else {
                observed.put(phaseName, pc);
              }
            }

            @Override
            public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
              record("req_headers", ctx);
            }

            @Override
            public void requestBody(RequestContext ctx, String body) {
              record("req_body", ctx);
            }

            @Override
            public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
              record("resp_headers", ctx);
            }

            @Override
            public void responseBody(RequestContext ctx, String body) {
              record("resp_body", ctx);
            }
          });

      EnvoyHttpClient.Response resp = client.send("POST", tc[1], caseHeaders(caseId), "probe-body");
      assertEquals(200, resp.status, resp.body);
      assertTrue(
          nilPhases.isEmpty(), tc[0] + " observed nil ProtocolConfig in phases: " + nilPhases);

      ProtocolConfig wantCfg = new ProtocolConfig(wantReq, wantResp, false);
      List<String> expectedPhases = new ArrayList<String>();
      expectedPhases.add("req_headers");
      expectedPhases.add("resp_headers");
      if (expectBodies) {
        expectedPhases.add("req_body");
        expectedPhases.add("resp_body");
      }
      for (String p : expectedPhases) {
        assertEquals(wantCfg, observed.get(p), tc[0] + " phase " + p);
      }
    }

    // 2. skip_request_headers_first_message
    String skipCaseId = id + "-skip-req-hdrs";
    final AtomicBoolean reqHdrsCalled = new AtomicBoolean(false);
    final Map<String, ProtocolConfig> skipObserved =
        Collections.synchronizedMap(new LinkedHashMap<String, ProtocolConfig>());
    final List<String> skipNilPhases = Collections.synchronizedList(new ArrayList<String>());
    defaultProcessor.register(
        skipCaseId,
        new ConformanceProcessor.CaseHandler() {
          private void record(String phaseName, RequestContext ctx) {
            ProtocolConfig pc = ctx.getProtocolConfig();
            if (pc == null) {
              skipNilPhases.add(phaseName);
            } else {
              skipObserved.put(phaseName, pc);
            }
          }

          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            reqHdrsCalled.set(true);
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            record("resp_headers", ctx);
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            record("resp_body", ctx);
          }
        });
    EnvoyHttpClient.Response skipResp =
        client.send("GET", "/mode/skip-req-headers/protocol-config", caseHeaders(skipCaseId), null);
    assertEquals(200, skipResp.status, skipResp.body);
    assertFalse(reqHdrsCalled.get());
    assertTrue(skipNilPhases.isEmpty(), "observed nil ProtocolConfig: " + skipNilPhases);
    ProtocolConfig wantSkipCfg =
        new ProtocolConfig(
            ModeOverride.BodySendMode.NONE, ModeOverride.BodySendMode.BUFFERED, false);
    assertEquals(wantSkipCfg, skipObserved.get("resp_headers"));
    assertEquals(wantSkipCfg, skipObserved.get("resp_body"));

    // 3. send_body_without_waiting_for_header_response
    String noWaitCaseId = id + "-no-wait";
    final Map<String, ProtocolConfig> noWaitObserved =
        Collections.synchronizedMap(new LinkedHashMap<String, ProtocolConfig>());
    final List<String> noWaitNilPhases = Collections.synchronizedList(new ArrayList<String>());
    defaultProcessor.register(
        noWaitCaseId,
        new ConformanceProcessor.CaseHandler() {
          private void record(String phaseName, RequestContext ctx) {
            ProtocolConfig pc = ctx.getProtocolConfig();
            if (pc == null) {
              noWaitNilPhases.add(phaseName);
            } else {
              noWaitObserved.put(phaseName, pc);
            }
          }

          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            record("req_headers", ctx);
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            record("req_body", ctx);
          }

          @Override
          public void responseHeaders(RequestContext ctx, Map<String, String> headers) {
            record("resp_headers", ctx);
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            record("resp_body", ctx);
          }
        });
    UpstreamServer.UpstreamEcho noWaitEcho =
        parseEcho(
            client.send(
                "POST",
                "/profile/send-body-without-waiting/check",
                caseHeaders(noWaitCaseId),
                "fast-stream-body"));
    assertEquals("fast-stream-body", noWaitEcho.body);
    assertTrue(noWaitNilPhases.isEmpty(), "observed nil ProtocolConfig: " + noWaitNilPhases);
    ProtocolConfig wantNoWaitCfg =
        new ProtocolConfig(
            ModeOverride.BodySendMode.STREAMED, ModeOverride.BodySendMode.STREAMED, true);
    for (String p : Arrays.asList("req_headers", "req_body", "resp_headers", "resp_body")) {
      assertEquals(wantNoWaitCfg, noWaitObserved.get(p), "phase " + p);
    }
    runner.assertNoProtocolErrors();

    // 4. default_mode_override_preserves_response_body_mode
    String defOverrideCaseId = id + "-default-override";
    final AtomicBoolean defRespBodyCalled = new AtomicBoolean(false);
    final AtomicBoolean defRespBodyNonEmpty = new AtomicBoolean(false);
    final AtomicReference<String> defReqBodySeen = new AtomicReference<String>("");
    final AtomicReference<String> defHeaderErr = new AtomicReference<String>(null);
    defaultProcessor.register(
        defOverrideCaseId,
        new ConformanceProcessor.CaseHandler() {
          @Override
          public void requestHeaders(RequestContext ctx, Map<String, String> headers) {
            ProtocolConfig want =
                new ProtocolConfig(
                    ModeOverride.BodySendMode.NONE, ModeOverride.BodySendMode.BUFFERED, false);
            if (!want.equals(ctx.getProtocolConfig())) {
              defHeaderErr.set("unexpected ProtocolConfig: " + ctx.getProtocolConfig());
              return;
            }
            ModeOverride mode = ctx.defaultModeOverride();
            mode.setRequestBodyMode(ModeOverride.BodySendMode.BUFFERED);
            ctx.overrideMode(mode);
          }

          @Override
          public void requestBody(RequestContext ctx, String body) {
            defReqBodySeen.set(body);
            ctx.replaceBody("mutated-req");
          }

          @Override
          public void responseBody(RequestContext ctx, String body) {
            defRespBodyCalled.set(true);
            defRespBodyNonEmpty.set(body != null && !body.isEmpty());
          }
        });
    UpstreamServer.UpstreamEcho defOverrideEcho =
        parseEcho(
            client.send(
                "POST",
                "/profile/mode-override-resp-buffered/check",
                caseHeaders(defOverrideCaseId),
                "raw-req"));
    assertEquals(null, defHeaderErr.get());
    assertEquals("raw-req", defReqBodySeen.get());
    assertEquals("mutated-req", defOverrideEcho.body);
    assertTrue(defRespBodyCalled.get());
    assertTrue(defRespBodyNonEmpty.get());
    runner.assertNoProtocolErrors();
  }
}
