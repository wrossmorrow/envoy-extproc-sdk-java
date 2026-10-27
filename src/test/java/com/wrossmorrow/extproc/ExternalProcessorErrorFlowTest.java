package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.github.wrossmorrow.extproc.processors.ThrowingRequestProcessor;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExternalProcessorErrorFlowTest {

  private static class FakeResponseObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> sent = new ArrayList<ProcessingResponse>();
    final List<Throwable> errors = new ArrayList<Throwable>();
    boolean completed = false;

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

  private static class ErroringRequestProcessor implements RequestProcessor {
    final ProcessingOptions options;
    final AtomicInteger errorHandlerCalls = new AtomicInteger();
    volatile RequestCase lastPhase;
    volatile Throwable lastError;

    public ErroringRequestProcessor(ProcessingOptions options) {
      this.options = options;
    }

    public String getName() {
      return "erroring";
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

  private static ProcessingRequest requestHeadersRequest() {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("GET"))
                        .build())
                .setEndOfStream(false)
                .build())
        .build();
  }

  private static ProcessingRequest requestHeadersThrowingRequest() {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(
                            HeaderValue.newBuilder().setKey("x-throw-exception").setValue("true"))
                        .build())
                .setEndOfStream(false)
                .build())
        .build();
  }

  @Test
  void phaseErrorWithoutAbortSendsEmptyContinueAndKeepsStreamAlive() {
    ErroringRequestProcessor processor = new ErroringRequestProcessor(new ProcessingOptions());
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(requestHeadersRequest());

    assertEquals(1, processor.errorHandlerCalls.get());
    assertEquals(RequestCase.REQUEST_HEADERS, processor.lastPhase);
    assertNotNull(processor.lastError);
    assertEquals("processor exploded", processor.lastError.getMessage());

    // without abort, the phase error results in an empty continue response, not a stream error
    assertEquals(1, responseObserver.sent.size());
    ProcessingResponse response = responseObserver.sent.get(0);
    assertEquals(ProcessingResponse.ResponseCase.REQUEST_HEADERS, response.getResponseCase());
    CommonResponse common = response.getRequestHeaders().getResponse();
    assertEquals(CommonResponse.ResponseStatus.CONTINUE, common.getStatus());
    assertTrue(responseObserver.errors.isEmpty());
    assertFalse(responseObserver.completed);
  }

  @Test
  void phaseErrorWithAbortPropagatesErrorToEnvoy() {
    ProcessingOptions options = new ProcessingOptions();
    options.abortOnProcessorFailure = true;
    ErroringRequestProcessor processor = new ErroringRequestProcessor(options);
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(requestHeadersRequest());

    assertEquals(1, processor.errorHandlerCalls.get());
    // the abort path propagates the error and does not send a response
    assertTrue(responseObserver.sent.isEmpty());
    assertEquals(1, responseObserver.errors.size());
    assertEquals(
        Status.Code.UNKNOWN,
        ((StatusRuntimeException) responseObserver.errors.get(0)).getStatus().getCode());
  }

  @Test
  void phaseErrorWithDefaultErrorHandlerSendsEmptyContinue() {
    ExternalProcessor externalProcessor =
        new ExternalProcessor(new ThrowingRequestProcessor(), null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(requestHeadersThrowingRequest());

    // the default error handler is a no-op, so the phase error still resolves to an empty continue
    assertEquals(1, responseObserver.sent.size());
    assertEquals(
        ProcessingResponse.ResponseCase.REQUEST_HEADERS,
        responseObserver.sent.get(0).getResponseCase());
    assertTrue(responseObserver.errors.isEmpty());
    assertFalse(responseObserver.completed);
  }

  @Test
  void streamCancellationNotifiesErrorHandlerWithoutErroring() {
    ErroringRequestProcessor processor = new ErroringRequestProcessor(new ProcessingOptions());
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onError(Status.CANCELLED.asRuntimeException());

    assertEquals(1, processor.errorHandlerCalls.get());
    assertNotNull(processor.lastError);
    assertEquals(
        Status.Code.CANCELLED,
        processor.lastError instanceof StatusRuntimeException
            ? ((StatusRuntimeException) processor.lastError).getStatus().getCode()
            : -1);
    assertTrue(responseObserver.errors.isEmpty());
  }
}
