package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;

class ExternalProcessorMessageTimeoutTest {

  private static class RecordingObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> sent = new ArrayList<ProcessingResponse>();
    final List<Throwable> errors = new ArrayList<Throwable>();
    boolean completed;

    @Override
    public void onNext(ProcessingResponse value) {
      sent.add(value);
    }

    @Override
    public void onError(Throwable t) {
      errors.add(t);
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  private abstract static class TimeoutProcessor implements RequestProcessor {
    final ProcessingOptions options = new ProcessingOptions();
    final AtomicBoolean errorCalled = new AtomicBoolean(false);
    final AtomicReference<RequestCase> errorPhase = new AtomicReference<RequestCase>();
    final AtomicReference<Throwable> errorSeen = new AtomicReference<Throwable>();

    @Override
    public String getName() {
      return "timeout-override-test";
    }

    @Override
    public ProcessingOptions getOptions() {
      return options;
    }

    @Override
    public void setHealthManager(RequestProcessorHealthManager health) {}

    @Override
    public void shutdown() {}

    @Override
    public void processRequestBody(RequestContext ctx, String body) {}

    @Override
    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

    @Override
    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

    @Override
    public void processResponseBody(RequestContext ctx, String body) {}

    @Override
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

    @Override
    public void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {
      errorCalled.set(true);
      errorPhase.set(phase);
      errorSeen.set(err);
    }
  }

  private static ProcessingRequest requestHeaders(boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("GET"))
                        .build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  @Test
  void sendsInterimTimeoutOverrideThenPhaseResponse() {
    RequestProcessor processor =
        new TimeoutProcessor() {
          @Override
          public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMessageTimeout(Duration.ofMillis(500));
            ctx.addHeader("x-after-extend", "true");
          }
        };
    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(requestHeaders(true));
    stream.onCompleted();

    assertEquals(2, observer.sent.size());
    assertTrue(observer.sent.get(0).hasOverrideMessageTimeout());
    assertEquals(0L, observer.sent.get(0).getOverrideMessageTimeout().getSeconds());
    assertEquals(500_000_000, observer.sent.get(0).getOverrideMessageTimeout().getNanos());

    ProcessingResponse finalResp = observer.sent.get(1);
    assertFalse(finalResp.hasOverrideMessageTimeout());
    HeaderMutation hm = finalResp.getRequestHeaders().getResponse().getHeaderMutation();
    assertEquals(1, hm.getSetHeadersCount());
    assertEquals("x-after-extend", hm.getSetHeaders(0).getHeader().getKey());
    assertTrue(observer.completed);
    assertTrue(observer.errors.isEmpty());
  }

  @Test
  void duplicateCallInSamePhaseRecordsPhaseError() {
    RequestProcessor processor =
        new TimeoutProcessor() {
          @Override
          public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMessageTimeout(Duration.ofMillis(500));
            ctx.overrideMessageTimeout(Duration.ofMillis(250));
          }
        };
    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(requestHeaders(true));
    stream.onCompleted();

    assertEquals(2, observer.sent.size());
    assertTrue(observer.sent.get(0).hasOverrideMessageTimeout());
    assertEquals(500_000_000, observer.sent.get(0).getOverrideMessageTimeout().getNanos());
    assertFalse(observer.sent.get(1).hasOverrideMessageTimeout());
    assertEquals(1.0, server.metrics.phaseErrors.get());
    assertEquals(1.0, server.metrics.erroredStreams.get());
  }

  @Test
  void sendCancellationDuringInterimOverrideIsGraceful() {
    TimeoutProcessor processor =
        new TimeoutProcessor() {
          @Override
          public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMessageTimeout(Duration.ofMillis(500));
          }
        };
    processor.options.abortOnProcessorFailure = true;

    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer =
        new RecordingObserver() {
          @Override
          public void onNext(ProcessingResponse value) {
            throw Status.CANCELLED.withDescription("client gone").asRuntimeException();
          }
        };
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(requestHeaders(true));

    assertTrue(processor.errorCalled.get());
    assertEquals(RequestCase.REQUEST_HEADERS, processor.errorPhase.get());
    assertTrue(processor.errorSeen.get() instanceof StatusRuntimeException);
    assertEquals(
        Status.Code.CANCELLED,
        ((StatusRuntimeException) processor.errorSeen.get()).getStatus().getCode());
    assertTrue(observer.errors.isEmpty());
    assertEquals(0.0, server.metrics.erroredStreams.get());
    assertEquals(0.0, server.metrics.phaseErrors.get());
    assertEquals(0.0, server.metrics.responseSendErrors.get());
  }

  @Test
  void sendFailureDuringInterimOverrideIncrementsSendErrors() {
    TimeoutProcessor processor =
        new TimeoutProcessor() {
          @Override
          public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
            ctx.overrideMessageTimeout(Duration.ofMillis(500));
          }
        };

    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer =
        new RecordingObserver() {
          @Override
          public void onNext(ProcessingResponse value) {
            throw Status.UNAVAILABLE.withDescription("transport closed").asRuntimeException();
          }
        };
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(requestHeaders(true));
    stream.onCompleted();

    assertTrue(processor.errorCalled.get());
    assertEquals(RequestCase.REQUEST_HEADERS, processor.errorPhase.get());
    assertEquals(1, observer.errors.size());
    assertEquals(
        Status.Code.UNKNOWN,
        ((StatusRuntimeException) observer.errors.get(0)).getStatus().getCode());
    assertEquals(1.0, server.metrics.responseSendErrors.get());
    assertEquals(1.0, server.metrics.erroredStreams.get());
    assertEquals(0.0, server.metrics.phaseErrors.get());
  }
}
