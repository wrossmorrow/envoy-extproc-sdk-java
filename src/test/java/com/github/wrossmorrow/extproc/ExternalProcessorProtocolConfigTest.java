package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProtocolConfiguration;
import com.google.protobuf.ByteString;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalProcessorProtocolConfigTest {

  private static final class RecordingObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> sent = new ArrayList<ProcessingResponse>();

    @Override
    public void onNext(ProcessingResponse value) {
      sent.add(value);
    }

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected stream error", t);
    }

    @Override
    public void onCompleted() {}
  }

  private static final class RecordingProcessor implements RequestProcessor {
    final List<ProtocolConfig> observed = new ArrayList<ProtocolConfig>();

    @Override
    public String getName() {
      return "protocol-config-recorder";
    }

    @Override
    public ProcessingOptions getOptions() {
      return new ProcessingOptions();
    }

    @Override
    public void setHealthManager(RequestProcessorHealthManager health) {}

    @Override
    public void shutdown() {}

    @Override
    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
      observed.add(ctx.getProtocolConfig());
    }

    @Override
    public void processRequestBody(RequestContext ctx, String body) {
      observed.add(ctx.getProtocolConfig());
    }

    @Override
    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
      observed.add(ctx.getProtocolConfig());
    }

    @Override
    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
      observed.add(ctx.getProtocolConfig());
    }

    @Override
    public void processResponseBody(RequestContext ctx, String body) {
      observed.add(ctx.getProtocolConfig());
    }

    @Override
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
      observed.add(ctx.getProtocolConfig());
    }
  }

  private static ProcessingRequest reqHeaders(boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST"))
                        .build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static ProcessingRequest respHeaders(boolean endOfStream) {
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

  private static ProcessingRequest reqBody(String payload) {
    return ProcessingRequest.newBuilder()
        .setRequestBody(
            HttpBody.newBuilder()
                .setBody(ByteString.copyFrom(payload, StandardCharsets.UTF_8))
                .setEndOfStream(true)
                .build())
        .build();
  }

  private static ProcessingRequest respBody(String payload) {
    return ProcessingRequest.newBuilder()
        .setResponseBody(
            HttpBody.newBuilder()
                .setBody(ByteString.copyFrom(payload, StandardCharsets.UTF_8))
                .setEndOfStream(true)
                .build())
        .build();
  }

  @Test
  void protocolConfigPersistsAcrossAllPhasesWhenOnlyFirstMessageSetsIt() {
    RecordingProcessor processor = new RecordingProcessor();
    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    ProcessingRequest firstReqHdrs =
        reqHeaders(false).toBuilder()
            .setProtocolConfig(
                ProtocolConfiguration.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.BUFFERED)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.STREAMED)
                    .setSendBodyWithoutWaitingForHeaderResponse(true)
                    .build())
            .build();
    stream.onNext(firstReqHdrs);
    stream.onNext(reqBody("req-payload"));
    stream.onNext(respHeaders(false));
    stream.onNext(respBody("resp-payload"));
    stream.onCompleted();

    assertEquals(4, processor.observed.size());
    ProtocolConfig want =
        new ProtocolConfig(
            ModeOverride.BodySendMode.BUFFERED, ModeOverride.BodySendMode.STREAMED, true);
    for (ProtocolConfig got : processor.observed) {
      assertNotNull(got);
      assertEquals(want, got);
    }
  }

  @Test
  void protocolConfigCapturedWhenRequestHeadersSkipped() {
    RecordingProcessor processor = new RecordingProcessor();
    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    ProcessingRequest firstRespHdrs =
        respHeaders(false).toBuilder()
            .setProtocolConfig(
                ProtocolConfiguration.newBuilder()
                    .setRequestBodyMode(ProcessingMode.BodySendMode.NONE)
                    .setResponseBodyMode(ProcessingMode.BodySendMode.BUFFERED)
                    .build())
            .build();
    stream.onNext(firstRespHdrs);
    stream.onNext(respBody("ok"));
    stream.onCompleted();

    assertEquals(2, processor.observed.size());
    ProtocolConfig want =
        new ProtocolConfig(
            ModeOverride.BodySendMode.NONE, ModeOverride.BodySendMode.BUFFERED, false);
    for (ProtocolConfig got : processor.observed) {
      assertNotNull(got);
      assertEquals(want, got);
    }
  }

  @Test
  void protocolConfigNullWhenAbsentOnStream() {
    RecordingProcessor processor = new RecordingProcessor();
    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(reqHeaders(true));
    stream.onNext(respHeaders(true));
    stream.onCompleted();

    assertEquals(2, processor.observed.size());
    for (ProtocolConfig got : processor.observed) {
      assertNull(got);
    }
  }
}
