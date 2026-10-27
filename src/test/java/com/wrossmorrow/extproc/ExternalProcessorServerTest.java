package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.github.wrossmorrow.extproc.processors.NoOpRequestProcessor;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.io.IOException;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Logger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.function.Executable;

class ServerTest {

  private static final long STREAM_RESPONSE_TIMEOUT_SECONDS = 10;

  private static final Logger logger = Logger.getLogger(ServerTest.class.getName());

  @Test
  void serverStartsWithoutException() throws IOException, InterruptedException {
    ExternalProcessorServer server =
        new ExternalProcessorServer()
            .builder()
            .addRequestProcessor(new NoOpRequestProcessor())
            .start();
    assertNotNull(server);
    server.stop();
  }

  @Test
  void serverOptionsDefaults() {
    ServerOptions options = ServerOptions.defaultOptions();
    assertEquals(50051, options.extProcPort);
    assertEquals(9090, options.metricsPort);
    assertEquals(1000, options.maxConcurrentStreams);
    assertEquals(5, options.unreadyPropagationDelaySeconds);
    assertEquals(10, options.terminationGracePeriodSeconds);
  }

  @Test
  void serverOptionsFromJsonLoadsValues() throws Exception {
    Path jsonPath =
        writeTempFile(
            "{\"extproc_port\": 50052, \"metrics_http_port\": 9091, \"max_concurrent_streams\": 42,"
                + " \"unready_propagation_delay_seconds\": 3, \"termination_grace_period_seconds\":"
                + " 7}");
    ServerOptions options = ServerOptions.fromJson(jsonPath.toString());
    assertEquals(50052, options.extProcPort);
    assertEquals(9091, options.metricsPort);
    assertEquals(42, options.maxConcurrentStreams);
    assertEquals(3, options.unreadyPropagationDelaySeconds);
    assertEquals(7, options.terminationGracePeriodSeconds);
  }

  @Test
  void serverOptionsFromJsonMissingFileFails() {
    Executable executable =
        () -> ServerOptions.fromJson("/tmp/definitely-missing-extproc-options.json");
    assertThrows(IOException.class, executable);
  }

  @Test
  void serverOptionsFromYamlLoadsValues() throws Exception {
    Path yamlPath =
        writeTempFile(
            "extproc_port: 50053\nmetrics_http_port: 9092\nmax_concurrent_streams: 43\n"
                + "unready_propagation_delay_seconds: 4\ntermination_grace_period_seconds: 8\n");
    ServerOptions options = ServerOptions.fromYaml(yamlPath.toString());
    assertEquals(50053, options.extProcPort);
    assertEquals(9092, options.metricsPort);
    assertEquals(43, options.maxConcurrentStreams);
    assertEquals(4, options.unreadyPropagationDelaySeconds);
    assertEquals(8, options.terminationGracePeriodSeconds);
  }

  @Test
  void serverOptionsFromYamlMissingFileFails() {
    Executable executable =
        () -> ServerOptions.fromYaml("/tmp/definitely-missing-extproc-options.yaml");
    assertThrows(IOException.class, executable);
  }

  @Test
  void processingOptionsFromJsonLoadsValues() throws Exception {
    Path jsonPath =
        writeTempFile(
            "{\"UpdateExtProcHeader\": true, \"UpdateDurationHeader\": true, "
                + "\"AbortOnProcessorFailure\": true}");
    ProcessingOptions options = ProcessingOptions.fromJson(jsonPath.toString());
    assertEquals(Boolean.TRUE, options.updateExtProcHeader);
    assertEquals(Boolean.TRUE, options.updateDurationHeader);
    assertEquals(Boolean.TRUE, options.abortOnProcessorFailure);
  }

  @Test
  void processingOptionsFromYamlLoadsValues() throws Exception {
    Path yamlPath =
        writeTempFile(
            "UpdateExtProcHeader: true\n"
                + "UpdateDurationHeader: true\n"
                + "AbortOnProcessorFailure: true\n");
    ProcessingOptions options = ProcessingOptions.fromYaml(yamlPath.toString());
    assertEquals(Boolean.TRUE, options.updateExtProcHeader);
    assertEquals(Boolean.TRUE, options.updateDurationHeader);
    assertEquals(Boolean.TRUE, options.abortOnProcessorFailure);
  }

  @Test
  void serverStartsAndStopsWithServerOptions() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.maxConcurrentStreams = 2;
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 10;

    ExternalProcessorServer server =
        new ExternalProcessorServer()
            .builder(options)
            .addRequestProcessor(new NoOpRequestProcessor())
            .start();
    assertNotNull(server);
    assertNotNull(server.server);
    assertNotNull(server.metricsServer);
    assertFalse(server.server.isTerminated());

    server.stop();

    assertTrue(server.server.isTerminated());
    assertNull(server.metricsServer);
  }

  @Test
  void maxConcurrentStreamsRejectsStreamsBeyondLimit() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.maxConcurrentStreams = 1;
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 30;

    ExternalProcessorServer server =
        new ExternalProcessorServer()
            .builder(options)
            .addRequestProcessor(new NoOpRequestProcessor())
            .start();
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    try {
      ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(channel);
      final CountDownLatch firstResponseLatch = new CountDownLatch(1);
      final StreamObserver<ProcessingRequest> firstRequestObserver =
          stub.process(
              new StreamObserver<ProcessingResponse>() {
                @Override
                public void onNext(ProcessingResponse value) {
                  firstResponseLatch.countDown();
                }

                @Override
                public void onError(Throwable t) {}

                @Override
                public void onCompleted() {}
              });
      final AtomicBoolean secondStreamRejected = new AtomicBoolean(false);
      final AtomicReference<Throwable> secondError = new AtomicReference<>();
      final CountDownLatch secondRejectedLatch = new CountDownLatch(1);
      final StreamObserver<ProcessingResponse> secondResponseObserver =
          new StreamObserver<ProcessingResponse>() {
            @Override
            public void onNext(ProcessingResponse value) {}

            @Override
            public void onError(Throwable t) {
              secondError.set(t);
              secondStreamRejected.set(true);
              secondRejectedLatch.countDown();
            }

            @Override
            public void onCompleted() {}
          };
      firstRequestObserver.onNext(requestHeadersRequest());
      // wait until the first stream has been accepted and responded to, so that the
      // max-concurrent-streams limiter has a live stream counted against it
      assertTrue(
          firstResponseLatch.await(STREAM_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "timed out waiting for first stream to be processed");

      final StreamObserver<ProcessingRequest> secondRequestObserver =
          stub.process(secondResponseObserver);
      secondRequestObserver.onNext(requestHeadersRequest());

      assertTrue(
          secondRejectedLatch.await(STREAM_RESPONSE_TIMEOUT_SECONDS, TimeUnit.SECONDS),
          "timed out waiting for second stream to be rejected");
      assertTrue(secondStreamRejected.get());
      StatusRuntimeException statusError = (StatusRuntimeException) secondError.get();
      assertEquals(Status.Code.RESOURCE_EXHAUSTED, statusError.getStatus().getCode());
    } finally {
      channel.shutdown();
      server.stop();
    }
  }

  // ------------------------------------------------------------------
  // Serve / MustServe entry points
  // ------------------------------------------------------------------

  @Test
  void serveRejectsNilProcessor() {
    ServerOptions options = ServerOptions.defaultOptions();
    Executable serve = () -> ExternalProcessorServer.serve(options, null, logger);
    IllegalArgumentException thrown = assertThrows(IllegalArgumentException.class, serve);
    assertEquals("processor is nil", thrown.getMessage());
  }

  @Test
  void serveBlocksUntilShutdownThenReturnsServer() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.maxConcurrentStreams = 0;
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 30;

    ExternalProcessorServer server =
        ExternalProcessorServer.startServerFromOptions(options, new NoOpRequestProcessor());
    ExecutorService executor = Executors.newSingleThreadExecutor();
    try {
      Future<ExternalProcessorServer> serverFuture =
          executor.submit(
              () -> {
                server.blockUntilShutdown();
                return server;
              });
      assertNotNull(server.server);
      assertNotNull(server.metricsServer);
      assertFalse(server.server.isTerminated());

      server.stop();

      ExternalProcessorServer returned = serverFuture.get(30, TimeUnit.SECONDS);
      assertSame(server, returned);
      assertTrue(server.server.isTerminated());
      assertNull(server.metricsServer);
    } finally {
      executor.shutdownNow();
    }
  }

  @Test
  void mustServeExitsWhenServeFails() {
    AtomicBoolean exited = new AtomicBoolean(false);
    ExternalProcessorServer.mustServeOrExit(
        ServerOptions.defaultOptions(), null, logger, () -> exited.set(true));
    assertTrue(exited.get());
  }

  // ------------------------------------------------------------------
  // Graceful shutdown
  // ------------------------------------------------------------------

  @Test
  void stopWaitsForUnreadyPropagationBeforeGracefulStop() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.unreadyPropagationDelaySeconds = 3;
    options.terminationGracePeriodSeconds = 30;

    ExternalProcessorServer server =
        ExternalProcessorServer.startServerFromOptions(options, new NoOpRequestProcessor());
    AtomicBoolean markedNotServing = new AtomicBoolean(false);
    server.addPreStopHook(() -> markedNotServing.set(true));

    long stopStartedMillis = System.currentTimeMillis();
    server.stop();
    long elapsedMillis = System.currentTimeMillis() - stopStartedMillis;

    assertTrue(
        markedNotServing.get(),
        "pre-stop hook should observe the service marked not serving before shutdown proceeds");
    assertTrue(
        elapsedMillis >= 2500,
        "expected stop to wait out the 3s unready propagation delay, took " + elapsedMillis + "ms");
    assertTrue(server.server.isTerminated());
    assertNull(server.metricsServer);
  }

  @Test
  void metricsShutdownHappensBeforePostStopHooks() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 30;

    ExternalProcessorServer server =
        ExternalProcessorServer.startServerFromOptions(options, new NoOpRequestProcessor());
    AtomicBoolean metricsUpDuringPreStop = new AtomicBoolean(false);
    server.addPreStopHook(() -> metricsUpDuringPreStop.set(server.metricsServer != null));

    AtomicBoolean metricsDownDuringPostStop = new AtomicBoolean(false);
    server.addPostStopHook(() -> metricsDownDuringPostStop.set(server.metricsServer == null));

    server.stop();

    assertTrue(
        metricsUpDuringPreStop.get(),
        "metrics server should still be serving during the shutdown sequence");
    assertTrue(
        metricsDownDuringPostStop.get(),
        "metrics server should be shut down before post-stop hooks run");
    assertNull(server.metricsServer);
    assertTrue(server.server.isTerminated());
  }

  @Test
  void processorCloseRunsAfterServerStoppedWithGracePeriod() throws Exception {
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = freePort();
    options.metricsPort = freePort();
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 7;

    CloseRecordingRequestProcessor processor = new CloseRecordingRequestProcessor();

    ExternalProcessorServer server =
        ExternalProcessorServer.startServerFromOptions(options, processor);
    AtomicBoolean serverTerminatedWhenClosed = new AtomicBoolean(false);
    server.addPostStopHook(() -> serverTerminatedWhenClosed.set(server.server.isTerminated()));

    server.stop();

    assertEquals(1, processor.closeCalls.get());
    assertEquals(7, processor.lastCloseGracePeriodSeconds.get());
    assertTrue(serverTerminatedWhenClosed.get());
  }

  @Test
  void stopForceStopsWhenGracefulShutdownTimesOut() throws Exception {
    int port = freePort();
    ServerOptions options = ServerOptions.defaultOptions();
    options.extProcPort = port;
    options.metricsPort = freePort();
    options.unreadyPropagationDelaySeconds = 0;
    options.terminationGracePeriodSeconds = 1;

    ExternalProcessorServer server =
        ExternalProcessorServer.startServerFromOptions(options, new HoldingRequestProcessor());
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress("localhost", port).usePlaintext().build();
    ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(channel);
    StreamObserver<ProcessingRequest> openRequestObserver =
        stub.process(
            new StreamObserver<ProcessingResponse>() {
              @Override
              public void onNext(ProcessingResponse value) {}

              @Override
              public void onError(Throwable t) {}

              @Override
              public void onCompleted() {}
            });
    openRequestObserver.onNext(requestHeadersRequest());
    // give the server a moment to register the in-flight stream before shutting down
    Thread.sleep(1000);

    long stopStartedMillis = System.currentTimeMillis();
    server.stop();
    long stopElapsedMillis = System.currentTimeMillis() - stopStartedMillis;

    assertTrue(server.server.isTerminated());
    assertTrue(
        stopElapsedMillis >= 900,
        "expected graceful shutdown to wait out the 1s grace period before force-stopping, took "
            + stopElapsedMillis
            + "ms");
    channel.shutdown();
  }

  private static class HoldingRequestProcessor implements RequestProcessor {
    public String getName() {
      return "holding";
    }

    public ProcessingOptions getOptions() {
      return new ProcessingOptions();
    }

    public void setHealthManager(RequestProcessorHealthManager health) {}

    public void shutdown() {}

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
      blockUntilInterrupted();
    }

    public void processRequestBody(RequestContext ctx, String body) {
      blockUntilInterrupted();
    }

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
      blockUntilInterrupted();
    }

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
      blockUntilInterrupted();
    }

    public void processResponseBody(RequestContext ctx, String body) {
      blockUntilInterrupted();
    }

    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
      blockUntilInterrupted();
    }

    private void blockUntilInterrupted() {
      try {
        Thread.sleep(Long.MAX_VALUE);
      } catch (InterruptedException e) {
        Thread.currentThread().interrupt();
      }
    }
  }

  private static class CloseRecordingRequestProcessor implements RequestProcessor {
    final AtomicInteger closeCalls = new AtomicInteger();
    final AtomicInteger lastCloseGracePeriodSeconds = new AtomicInteger(-1);

    public String getName() {
      return "close-recording";
    }

    public ProcessingOptions getOptions() {
      return new ProcessingOptions();
    }

    public void setHealthManager(RequestProcessorHealthManager health) {}

    public void shutdown() {}

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {}

    public void processRequestBody(RequestContext ctx, String body) {}

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

    public void processResponseBody(RequestContext ctx, String body) {}

    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

    public void close(int gracePeriodSeconds) {
      closeCalls.incrementAndGet();
      lastCloseGracePeriodSeconds.set(gracePeriodSeconds);
    }
  }

  private static ProcessingRequest requestHeadersRequest() {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey("x-test").setValue("1").build())
                        .build())
                .build())
        .build();
  }

  private static Path writeTempFile(String content) throws IOException {
    Path path = Files.createTempFile("extproc-options", ".json");
    Files.write(path, content.getBytes(StandardCharsets.UTF_8));
    return path;
  }

  private static int freePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }
}
