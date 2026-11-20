package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.HttpTrailers;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.ByteString;
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
    volatile Boolean lastRequestTrailersEndOfStream;
    volatile Boolean lastResponseTrailersEndOfStream;
    volatile Map<String, String> lastRequestTrailers;
    volatile Map<String, String> lastResponseTrailers;

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

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
      lastRequestTrailersEndOfStream = ctx.streamComplete();
      lastRequestTrailers = trailers;
    }

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

    public void processResponseBody(RequestContext ctx, String body) {}

    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
      lastResponseTrailersEndOfStream = ctx.streamComplete();
      lastResponseTrailers = trailers;
    }

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
    assertEquals("", setHeaders.get(0).getHeader().getValue());
    assertEquals("testproc", setHeaders.get(0).getHeader().getRawValue().toStringUtf8());
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
    assertEquals("", setHeaders.get(0).getHeader().getValue());
    String durationValue = setHeaders.get(0).getHeader().getRawValue().toStringUtf8();
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
    assertFalse(response.getResponseHeaders().getResponse().hasHeaderMutation());
    assertFalse(response.getResponseHeaders().getResponse().hasBodyMutation());
    assertTrue(setHeaders(response).isEmpty());
  }

  @Test
  void durationHeadersAreNeverInjectedOnBodyPhases() {
    ProcessingOptions options = new ProcessingOptions();
    options.upstreamDurationHeader = true;
    options.downstreamDurationHeader = true;
    options.updateDurationHeader = true;
    ExternalProcessor externalProcessor =
        new ExternalProcessor(new OptionsRequestProcessor(options), null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST"))
                            .build())
                    .setEndOfStream(false)
                    .build())
            .build());
    serverObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("req"))
                    .setEndOfStream(true)
                    .build())
            .build());
    serverObserver.onNext(responseHeadersRequest(false));
    serverObserver.onNext(
        ProcessingRequest.newBuilder()
            .setResponseBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("resp"))
                    .setEndOfStream(true)
                    .build())
            .build());

    assertEquals(4, responseObserver.sent.size());
    // REQUEST_HEADERS has upstreamDurationHeader
    assertTrue(responseObserver.sent.get(0).getRequestHeaders().getResponse().hasHeaderMutation());
    // REQUEST_BODY must NOT get duration headers
    assertFalse(responseObserver.sent.get(1).getRequestBody().getResponse().hasHeaderMutation());
    // RESPONSE_HEADERS has downstreamDurationHeader
    assertTrue(responseObserver.sent.get(2).getResponseHeaders().getResponse().hasHeaderMutation());
    // RESPONSE_BODY must NOT get duration headers (even with endOfStream=true)
    assertFalse(responseObserver.sent.get(3).getResponseBody().getResponse().hasHeaderMutation());
  }

  @Test
  void trailersSetEndOfStreamDecodeRawValuesAndSupportDurationHeaders() {
    ProcessingOptions options = new ProcessingOptions();
    options.upstreamDurationHeader = true;
    options.updateDurationHeader = true;
    OptionsRequestProcessor processor = new OptionsRequestProcessor(options);
    ExternalProcessor externalProcessor = new ExternalProcessor(processor, null);
    FakeResponseObserver responseObserver = new FakeResponseObserver();
    StreamObserver<ProcessingRequest> serverObserver = externalProcessor.process(responseObserver);

    serverObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestTrailers(
                HttpTrailers.newBuilder()
                    .setTrailers(
                        HeaderMap.newBuilder()
                            .addHeaders(
                                HeaderValue.newBuilder()
                                    .setKey("x-req-raw")
                                    .setRawValue(ByteString.copyFromUtf8("raw-req-val")))
                            .addHeaders(
                                HeaderValue.newBuilder()
                                    .setKey("x-req-str")
                                    .setValue("str-req-val"))
                            .build())
                    .build())
            .build());

    serverObserver.onNext(
        ProcessingRequest.newBuilder()
            .setResponseTrailers(
                HttpTrailers.newBuilder()
                    .setTrailers(
                        HeaderMap.newBuilder()
                            .addHeaders(
                                HeaderValue.newBuilder()
                                    .setKey("x-resp-raw")
                                    .setRawValue(ByteString.copyFromUtf8("raw-resp-val")))
                            .build())
                    .build())
            .build());

    assertEquals(Boolean.TRUE, processor.lastRequestTrailersEndOfStream);
    assertEquals("raw-req-val", processor.lastRequestTrailers.get("x-req-raw"));
    assertEquals("str-req-val", processor.lastRequestTrailers.get("x-req-str"));

    assertEquals(Boolean.TRUE, processor.lastResponseTrailersEndOfStream);
    assertEquals("raw-resp-val", processor.lastResponseTrailers.get("x-resp-raw"));

    assertEquals(2, responseObserver.sent.size());
    assertTrue(responseObserver.sent.get(0).getRequestTrailers().hasHeaderMutation());
    assertEquals(
        "x-extproc-duration-ns",
        responseObserver
            .sent
            .get(0)
            .getRequestTrailers()
            .getHeaderMutation()
            .getSetHeaders(0)
            .getHeader()
            .getKey());
    assertTrue(responseObserver.sent.get(1).getResponseTrailers().hasHeaderMutation());
    assertEquals(
        "x-extproc-duration-ns",
        responseObserver
            .sent
            .get(1)
            .getResponseTrailers()
            .getHeaderMutation()
            .getSetHeaders(0)
            .getHeader()
            .getKey());
  }
}
