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
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ServerTest {

  private static final long STREAM_RESPONSE_TIMEOUT_SECONDS = 10;

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

  private static int freePort() throws IOException {
    ServerSocket socket = new ServerSocket(0);
    int port = socket.getLocalPort();
    socket.close();
    return port;
  }
}
