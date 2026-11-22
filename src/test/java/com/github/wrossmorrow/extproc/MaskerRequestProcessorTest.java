package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.github.wrossmorrow.extproc.processors.MaskerRequestProcessor;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MaskerRequestProcessorTest {

  private static final class RecordingObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> responses = new ArrayList<ProcessingResponse>();

    @Override
    public void onNext(ProcessingResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected stream error", t);
    }

    @Override
    public void onCompleted() {}
  }

  private static ProcessingRequest requestHeaders(String contentType) {
    return requestHeaders(contentType, false);
  }

  private static ProcessingRequest requestHeaders(String contentType, boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST"))
                        .addHeaders(HeaderValue.newBuilder().setKey(":path").setValue("/mask"))
                        .addHeaders(
                            HeaderValue.newBuilder().setKey("content-type").setValue(contentType))
                        .build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  @Test
  void jsonRequestOptsIntoBufferedRequestBodyAndMasksFields() {
    MaskerRequestProcessor processor = new MaskerRequestProcessor();
    ExternalProcessor extProc = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = extProc.process(observer);

    stream.onNext(requestHeaders("application/json; charset=utf-8"));
    assertEquals(1, observer.responses.size());
    ProcessingResponse hdrResp = observer.responses.get(0);
    assertTrue(hdrResp.hasModeOverride());
    assertEquals(
        ProcessingMode.BodySendMode.BUFFERED, hdrResp.getModeOverride().getRequestBodyMode());
    assertEquals(
        ProcessingMode.HeaderSendMode.SKIP, hdrResp.getModeOverride().getResponseHeaderMode());
    assertEquals(ProcessingMode.BodySendMode.NONE, hdrResp.getModeOverride().getResponseBodyMode());

    stream.onNext(
        ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(
                        ByteString.copyFrom(
                            "{\"maskme\":\"secret\",\"keep\":\"ok\"}", StandardCharsets.UTF_8))
                    .setEndOfStream(true)
                    .build())
            .build());
    assertEquals(2, observer.responses.size());
    ProcessingResponse bodyResp = observer.responses.get(1);
    assertFalse(bodyResp.hasModeOverride());
    String replaced =
        bodyResp.getRequestBody().getResponse().getBodyMutation().getBody().toStringUtf8();
    assertTrue(replaced.contains("\"maskme\":\"****\""), replaced);
    assertTrue(replaced.contains("\"keep\":\"ok\""), replaced);
    assertFalse(replaced.contains("secret"));
  }

  @Test
  void nonJsonRequestSkipsRemainingPhasesUnlessDurationHeadersEnabled() {
    MaskerRequestProcessor processor = new MaskerRequestProcessor();
    ExternalProcessor extProc = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = extProc.process(observer);

    stream.onNext(requestHeaders("text/plain"));
    assertEquals(1, observer.responses.size());
    ProcessingResponse skipResp = observer.responses.get(0);
    assertTrue(skipResp.hasModeOverride());
    assertEquals(
        ProcessingMode.HeaderSendMode.SKIP, skipResp.getModeOverride().getResponseHeaderMode());
    assertEquals(ProcessingMode.BodySendMode.NONE, skipResp.getModeOverride().getRequestBodyMode());
    assertEquals(
        ProcessingMode.BodySendMode.NONE, skipResp.getModeOverride().getResponseBodyMode());
    assertEquals(
        ProcessingMode.HeaderSendMode.SKIP, skipResp.getModeOverride().getRequestTrailerMode());
    assertEquals(
        ProcessingMode.HeaderSendMode.SKIP, skipResp.getModeOverride().getResponseTrailerMode());

    processor.getOptions().downstreamDurationHeader = true;
    RecordingObserver durationObserver = new RecordingObserver();
    StreamObserver<ProcessingRequest> durationStream = extProc.process(durationObserver);
    durationStream.onNext(requestHeaders("text/plain"));
    ProcessingResponse hdrResp = durationObserver.responses.get(0);
    assertTrue(hdrResp.hasModeOverride());
    assertEquals(
        ProcessingMode.HeaderSendMode.SEND, hdrResp.getModeOverride().getResponseHeaderMode());
    assertEquals(ProcessingMode.BodySendMode.NONE, hdrResp.getModeOverride().getRequestBodyMode());

    durationStream.onNext(
        ProcessingRequest.newBuilder()
            .setResponseHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("200"))
                            .addHeaders(
                                HeaderValue.newBuilder()
                                    .setKey("content-type")
                                    .setValue("application/json"))
                            .build())
                    .build())
            .build());
    ProcessingResponse respHdr = durationObserver.responses.get(1);
    assertFalse(respHdr.hasModeOverride());

    MaskerRequestProcessor eosProcessor = new MaskerRequestProcessor();
    RecordingObserver eosObserver = new RecordingObserver();
    StreamObserver<ProcessingRequest> eosStream =
        new ExternalProcessor(eosProcessor, null).process(eosObserver);
    eosStream.onNext(requestHeaders("application/json", true));
    assertEquals(1, eosObserver.responses.size());
    ProcessingResponse eosResp = eosObserver.responses.get(0);
    assertTrue(eosResp.hasModeOverride());
    assertEquals(ProcessingMode.BodySendMode.NONE, eosResp.getModeOverride().getRequestBodyMode());
    assertEquals(
        ProcessingMode.HeaderSendMode.SKIP, eosResp.getModeOverride().getResponseHeaderMode());
  }
}
