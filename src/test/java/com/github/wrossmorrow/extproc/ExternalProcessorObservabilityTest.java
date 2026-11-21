package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.HttpTrailers;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

/**
 * Covers the envoy ext_proc observability mode ({@code ProcessingRequest.observability_mode =
 * true}) wiring: phase callbacks run with the context flag set, the SDK suppresses the {@link
 * ProcessingResponse} for every phase, and the error paths skip the empty-continue response while
 * still surfacing processor errors per the abort policy.
 */
class ExternalProcessorObservabilityTest {

  private static class FakeResponseObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> sent = new ArrayList<ProcessingResponse>();
    final List<Throwable> errors = new ArrayList<Throwable>();
    boolean completed = false;
    final AtomicInteger completedCount = new AtomicInteger();

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
      completedCount.incrementAndGet();
    }
  }

  /** Records the phases observed, with whether observability mode was active, and continues. */
  private static class ObservabilityRecordingRequestProcessor implements RequestProcessor {
    final ProcessingOptions options;
    final List<RequestCase> recordedPhases = new ArrayList<RequestCase>();
    final List<Boolean> recordedObsModes = new ArrayList<Boolean>();

    public ObservabilityRecordingRequestProcessor(ProcessingOptions options) {
      this.options = options;
    }

    public String getName() {
      return "observability-recorder";
    }

    public ProcessingOptions getOptions() {
      return options;
    }

    public void setHealthManager(RequestProcessorHealthManager health) {}

    public void shutdown() {}

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
      record(ctx);
    }

    public void processRequestBody(RequestContext ctx, String body) {
      record(ctx);
    }

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
      record(ctx);
    }

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
      record(ctx);
      // observability mode suppresses the SDK's own synthetic headers (x-extproc-names,
      // x-extproc-duration-ns) while still letting the processor observe the phase
      if (ctx.isObservabilityMode()) {
        assertFalse(ctx.responseHeaders.containsKey("x-extproc-names"));
      }
    }

    public void processResponseBody(RequestContext ctx, String body) {
      record(ctx);
      // observability mode suppresses the SDK's own synthetic duration header
      if (ctx.isObservabilityMode()) {
        assertFalse(ctx.responseHeaders.containsKey("x-extproc-duration-ns"));
      }
    }

    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
      record(ctx);
    }

    public void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {}

    private void record(RequestContext ctx) {
      recordedPhases.add(ctx.getPhase());
      recordedObsModes.add(ctx.isObservabilityMode());
      if (ctx.isObservabilityMode()) {
        // the context must expose the phase it is handling to the processor
        assertNotNull(ctx.getPhaseName());
      }
    }
  }

  private static class ErroringRequestProcessor implements RequestProcessor {
    final ProcessingOptions options;
    final AtomicInteger errorHandlerCalls = new AtomicInteger();
    volatile RequestCase lastPhase;
    volatile Throwable lastError;

    public ErroringRequestProcessor(ProcessingOptions options) {
      this.options = options;
    }

    public String getName() {
      return "observability-errorer";
    }

    public ProcessingOptions getOptions() {
      return options;
    }

    public void setHealthManager(RequestProcessorHealthManager health) {}

    public void shutdown() {}

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
      throw new RuntimeException("processor exploded");
    }

    public void processRequestBody(RequestContext ctx, String body) {}

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

    public void processResponseBody(RequestContext ctx, String body) {}

    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

    public void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {
      errorHandlerCalls.incrementAndGet();
      lastPhase = phase;
      lastError = err;
    }
  }

  private static ProcessingRequest observabilityRequest(RequestCase phase, boolean endOfStream) {
    switch (phase) {
      case REQUEST_HEADERS:
        return ProcessingRequest.newBuilder()
            .setRequestHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(
                                HeaderValue.newBuilder().setKey(":method").setValue("POST").build())
                            .build())
                    .setEndOfStream(endOfStream)
                    .build())
            .setObservabilityMode(true)
            .build();
      case REQUEST_BODY:
        return ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("hello"))
                    .setEndOfStream(endOfStream)
                    .build())
            .setObservabilityMode(true)
            .build();
      case RESPONSE_HEADERS:
        return ProcessingRequest.newBuilder()
            .setResponseHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(
                                HeaderValue.newBuilder().setKey(":status").setValue("200").build())
                            .build())
                    .setEndOfStream(endOfStream)
                    .build())
            .setObservabilityMode(true)
            .build();
      case RESPONSE_BODY:
        return ProcessingRequest.newBuilder()
            .setResponseBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("world"))
                    .setEndOfStream(endOfStream)
                    .build())
            .setObservabilityMode(true)
            .build();
      case RESPONSE_TRAILERS:
        return ProcessingRequest.newBuilder()
            .setResponseTrailers(
                HttpTrailers.newBuilder()
                    .setTrailers(
                        HeaderMap.newBuilder()
                            .addHeaders(
                                HeaderValue.newBuilder().setKey("x-trailer").setValue("1").build())
                            .build())
                    .build())
            .setObservabilityMode(true)
            .build();
      default:
        throw new RuntimeException("unexpected phase for observability test: " + phase);
    }
  }

  private static ProcessingRequest observabilityErrorRequest() {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(
                            HeaderValue.newBuilder()
                                .setKey("x-throw-exception")
                                .setValue("true")
                                .build())
                        .build())
                .setEndOfStream(false)
                .build())
        .setObservabilityMode(true)
        .build();
  }

  @Test
  void observabilityModeSuppressesResponsesWhileRunningPhaseCallbacks() {
    ProcessingOptions options = ProcessingOptions.newDefaultProcessingOptions();
    options.updateExtProcHeader = true;
    options.updateDurationHeader = true;

    ObservabilityRecordingRequestProcessor processor =
        new ObservabilityRecordingRequestProcessor(options);
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    // full request flow, with observability mode active on every phase
    serverObserver.onNext(observabilityRequest(RequestCase.REQUEST_HEADERS, false));
    serverObserver.onNext(observabilityRequest(RequestCase.REQUEST_BODY, true));
    serverObserver.onNext(observabilityRequest(RequestCase.RESPONSE_HEADERS, false));
    serverObserver.onNext(observabilityRequest(RequestCase.RESPONSE_BODY, true));
    serverObserver.onCompleted();

    // no ProcessingResponse may be sent back to envoy while observing
    assertTrue(responseObserver.sent.isEmpty());
    assertTrue(responseObserver.errors.isEmpty());
    assertTrue(responseObserver.completed);
    assertEquals(0, externalProcessor.metrics.phaseErrors.get(), 0.0);
    assertEquals(0, externalProcessor.metrics.totalEmptyResponses.get(), 0.0);

    // the phase callbacks ran, in order, with observability mode enabled
    assertEquals(
        java.util.Arrays.asList(
            RequestCase.REQUEST_HEADERS,
            RequestCase.REQUEST_BODY,
            RequestCase.RESPONSE_HEADERS,
            RequestCase.RESPONSE_BODY),
        processor.recordedPhases);
    assertEquals(java.util.Arrays.asList(true, true, true, true), processor.recordedObsModes);

    // metrics still recorded phase activity and body byte counts
    assertEquals(1, externalProcessor.metrics.totalStreams.get());
    assertEquals(1, externalProcessor.metrics.totalRequestHeaders.get());
    assertEquals(1, externalProcessor.metrics.totalRequestBody.get());
    assertEquals(5, externalProcessor.metrics.requestBodyBytes.get());
    assertEquals(1, externalProcessor.metrics.totalResponseHeaders.get());
    assertEquals(1, externalProcessor.metrics.totalResponseBody.get());
    assertEquals(5, externalProcessor.metrics.responseBodyBytes.get());
  }

  @Test
  void observabilityModeErrorWithoutAbortSendsNoEmptyContinue() {
    ProcessingOptions options = ProcessingOptions.newDefaultProcessingOptions();
    options.updateExtProcHeader = true;
    options.updateDurationHeader = true;
    options.abortOnProcessorFailure = false;

    ErroringRequestProcessor processor = new ErroringRequestProcessor(options);
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(observabilityErrorRequest());

    // the phase error was surfaced to the error handler, but no response was sent and no
    // empty-continue was synthesized, since observability mode suppresses responses
    assertEquals(1, processor.errorHandlerCalls.get());
    assertEquals(RequestCase.REQUEST_HEADERS, processor.lastPhase);
    assertNotNull(processor.lastError);
    assertEquals("processor exploded", processor.lastError.getMessage());
    assertTrue(responseObserver.sent.isEmpty());
    assertTrue(responseObserver.errors.isEmpty());
    assertEquals(0, externalProcessor.metrics.totalEmptyResponses.get());
    assertEquals(1, externalProcessor.metrics.phaseErrors.get());

    serverObserver.onCompleted();
    assertTrue(responseObserver.completed);
    assertTrue(responseObserver.errors.isEmpty());
    assertEquals(1, externalProcessor.metrics.erroredStreams.get());
  }

  @Test
  void observabilityModeErrorWithAbortPropagatesErrorToEnvoy() {
    ProcessingOptions options = ProcessingOptions.newDefaultProcessingOptions();
    options.abortOnProcessorFailure = true;

    ErroringRequestProcessor processor = new ErroringRequestProcessor(options);
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(observabilityErrorRequest());

    // observability mode still honors the abort policy: errors abort the stream as usual
    assertTrue(responseObserver.sent.isEmpty());
    assertEquals(1, responseObserver.errors.size());
    assertEquals(
        Status.Code.UNKNOWN,
        ((StatusRuntimeException) responseObserver.errors.get(0)).getStatus().getCode());
    assertEquals(1, externalProcessor.metrics.phaseErrors.get());
  }
}
