package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.Metadata;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.Any;
import com.google.protobuf.ByteString;
import com.google.protobuf.StringValue;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExternalProcessorMetadataTest {

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

  @Test
  void inboundMetadataAndAttributesPersistAndMergeAcrossPhases() throws Exception {
    final Map<String, Map<String, Object>> capturedMeta =
        new LinkedHashMap<String, Map<String, Object>>();
    final Map<String, Any> capturedTyped = new LinkedHashMap<String, Any>();
    final Map<String, Object> capturedAttrs = new LinkedHashMap<String, Object>();

    RequestProcessor processor =
        new RequestProcessor() {
          @Override
          public String getName() {
            return "metadata-attrs";
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
          public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {}

          @Override
          public void processRequestBody(RequestContext ctx, String body) {
            ctx.setDynamicMetadata("com.example.out", "checked", true);
          }

          @Override
          public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

          @Override
          public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

          @Override
          public void processResponseBody(RequestContext ctx, String body) {
            for (Map.Entry<String, Map<String, Object>> entry : ctx.getMetadata().entrySet()) {
              capturedMeta.put(entry.getKey(), new LinkedHashMap<String, Object>(entry.getValue()));
            }
            capturedTyped.putAll(ctx.getTypedMetadata());
            capturedAttrs.putAll(ctx.getAttributes());
          }

          @Override
          public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
        };

    ExternalProcessor server = new ExternalProcessor(processor, null);
    RecordingObserver observer = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream = server.process(observer);

    stream.onNext(
        ProcessingRequest.newBuilder()
            .setRequestHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("POST"))
                            .build())
                    .build())
            .setMetadataContext(
                Metadata.newBuilder()
                    .putFilterMetadata(
                        "com.example.auth",
                        Struct.newBuilder()
                            .putFields("sub", Value.newBuilder().setStringValue("alice").build())
                            .build())
                    .putTypedFilterMetadata(
                        "com.example.typed", Any.pack(StringValue.of("typed-claim")))
                    .build())
            .putAttributes(
                "envoy.filters.http.ext_proc",
                Struct.newBuilder()
                    .putFields(
                        "request.protocol", Value.newBuilder().setStringValue("HTTP/2").build())
                    .build())
            .build());

    stream.onNext(
        ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFrom("req-body", StandardCharsets.UTF_8))
                    .setEndOfStream(true)
                    .build())
            .build());

    stream.onNext(
        ProcessingRequest.newBuilder()
            .setResponseHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(
                        HeaderMap.newBuilder()
                            .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("200"))
                            .build())
                    .build())
            .setMetadataContext(
                Metadata.newBuilder()
                    .putFilterMetadata(
                        "com.example.upstream",
                        Struct.newBuilder()
                            .putFields(
                                "region", Value.newBuilder().setStringValue("us-west").build())
                            .build())
                    .build())
            .putAttributes(
                "envoy.filters.http.ext_proc",
                Struct.newBuilder()
                    .putFields("response.code", Value.newBuilder().setNumberValue(200).build())
                    .build())
            .build());

    stream.onNext(
        ProcessingRequest.newBuilder()
            .setResponseBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFrom("resp-body", StandardCharsets.UTF_8))
                    .setEndOfStream(true)
                    .build())
            .build());
    stream.onCompleted();

    assertEquals(4, observer.sent.size());
    assertEquals("alice", capturedMeta.get("com.example.auth").get("sub"));
    assertEquals("us-west", capturedMeta.get("com.example.upstream").get("region"));
    Any typed = capturedTyped.get("com.example.typed");
    assertNotNull(typed);
    assertEquals("typed-claim", typed.unpack(StringValue.class).getValue());
    assertEquals("HTTP/2", capturedAttrs.get("request.protocol"));
    assertEquals(Double.valueOf(200.0), capturedAttrs.get("response.code"));

    ProcessingResponse reqBodyResp = observer.sent.get(1);
    assertTrue(reqBodyResp.hasDynamicMetadata());
    assertTrue(
        reqBodyResp
            .getDynamicMetadata()
            .getFieldsMap()
            .get("com.example.out")
            .getStructValue()
            .getFieldsMap()
            .get("checked")
            .getBoolValue());
    assertFalse(observer.sent.get(2).hasDynamicMetadata());
  }
}
