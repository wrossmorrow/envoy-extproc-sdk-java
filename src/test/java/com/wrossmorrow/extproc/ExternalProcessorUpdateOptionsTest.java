package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExternalProcessorUpdateOptionsTest {

  private static class OptionsRequestProcessor implements RequestProcessor {
    final ProcessingOptions options;
    final AtomicInteger errorHandlerCalls = new AtomicInteger();
    volatile RequestCase lastPhase;
    volatile Throwable lastError;

    public OptionsRequestProcessor(ProcessingOptions options) {
      this.options = options;
    }

    public String getName() {
      return "testproc";
    }

    public ProcessingOptions getOptions() {
      return options;
    }

    public void setHealthManager(RequestProcessorHealthManager health) {}

    public void shutdown() {}

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {}

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

  private static ProcessingRequest responseHeadersRequest(boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setResponseHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("200"))
                        .build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static List<HeaderValueOption> setHeaders(ProcessingResponse response) {
    return response.getResponseHeaders().getResponse().getHeaderMutation().getSetHeadersList();
  }

  @Test
  void updateExtProcHeaderAppendsNamesHeaderDuringResponseHeaders() {
    ProcessingOptions options = new ProcessingOptions();
    options.updateExtProcHeader = true;
    ExternalProcessor externalProcessor =
        new ExternalProcessor(new OptionsRequestProcessor(options), null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(responseHeadersRequest(true));

    assertEquals(1, responseObserver.sent.size());
    ProcessingResponse response = responseObserver.sent.get(0);
    assertEquals(ProcessingResponse.ResponseCase.RESPONSE_HEADERS, response.getResponseCase());
    List<HeaderValueOption> setHeaders = setHeaders(response);
    assertEquals(1, setHeaders.size());
    assertEquals("x-extproc-names", setHeaders.get(0).getHeader().getKey());
    assertEquals("testproc", setHeaders.get(0).getHeader().getValue());
  }

  @Test
  void updateDurationHeaderAppendsDurationOnEndOfStreamResponse() {
    ProcessingOptions options = new ProcessingOptions();
    options.updateDurationHeader = true;
    ExternalProcessor externalProcessor =
        new ExternalProcessor(new OptionsRequestProcessor(options), null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(responseHeadersRequest(true));

    assertEquals(1, responseObserver.sent.size());
    ProcessingResponse response = responseObserver.sent.get(0);
    assertEquals(ProcessingResponse.ResponseCase.RESPONSE_HEADERS, response.getResponseCase());
    List<HeaderValueOption> setHeaders = setHeaders(response);
    assertEquals(1, setHeaders.size());
    assertEquals("x-extproc-duration-ns", setHeaders.get(0).getHeader().getKey());
    String durationValue = setHeaders.get(0).getHeader().getValue();
    assertTrue(Long.parseLong(durationValue) > 0);
  }

  @Test
  void updateOptionsDisabledDoNotAppendSyntheticHeaders() {
    ExternalProcessor externalProcessor =
        new ExternalProcessor(new OptionsRequestProcessor(new ProcessingOptions()), null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(responseHeadersRequest(true));

    assertEquals(1, responseObserver.sent.size());
    ProcessingResponse response = responseObserver.sent.get(0);
    assertEquals(ProcessingResponse.ResponseCase.RESPONSE_HEADERS, response.getResponseCase());
    assertTrue(setHeaders(response).isEmpty());
  }
}
