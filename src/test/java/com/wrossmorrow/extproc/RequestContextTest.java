package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.type.v3.StatusCode;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ContextTest {

  @Test
  void initializationStartsTimer() {
    Instant testStarted = Instant.now();
    RequestContext ctx = new RequestContext();
    assertNotNull(ctx.started);
    assertTrue(ctx.started.isAfter(testStarted));
  }

  @Test
  void resetKeepsTimerOn() {
    RequestContext ctx = new RequestContext();
    Instant postCreate = Instant.now();
    ctx.reset(RequestCase.REQUEST_BODY);
    assertNotNull(ctx.started);
    assertTrue(ctx.started.isBefore(postCreate));
  }

  @Test
  void addHeadersAddsHeaders() {
    RequestContext ctx = new RequestContext();
    assertEquals(ctx.addHeaders.size(), 0);

    ctx.addHeader("header1", "value");
    ctx.addHeader("header2", "value");
    assertEquals(ctx.addHeaders.size(), 2);

    ctx.addHeader("header3", "value");
    assertEquals(ctx.addHeaders.size(), 3);

    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    HeaderMutation hm = response.getRequestHeaders().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 3);
    assertEquals(hm.getRemoveHeadersCount(), 0);

    response = ctx.getResponse(RequestCase.REQUEST_BODY);
    hm = response.getRequestBody().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 3);
    assertEquals(hm.getRemoveHeadersCount(), 0);

    response = ctx.getResponse(RequestCase.RESPONSE_HEADERS);
    hm = response.getResponseHeaders().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 3);
    assertEquals(hm.getRemoveHeadersCount(), 0);

    response = ctx.getResponse(RequestCase.RESPONSE_BODY);
    hm = response.getResponseBody().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 3);
    assertEquals(hm.getRemoveHeadersCount(), 0);
  }

  @Test
  void removeHeaderRemovesHeaders() {
    RequestContext ctx = new RequestContext();
    assertEquals(ctx.removeHeaders.size(), 0);

    ctx.removeHeader("header1");
    assertEquals(ctx.removeHeaders.size(), 1);

    ctx.removeHeader("header2");
    assertEquals(ctx.removeHeaders.size(), 2);

    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    HeaderMutation hm = response.getRequestHeaders().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 0);
    assertEquals(hm.getRemoveHeadersCount(), 2);

    response = ctx.getResponse(RequestCase.REQUEST_BODY);
    hm = response.getRequestBody().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 0);
    assertEquals(hm.getRemoveHeadersCount(), 2);

    response = ctx.getResponse(RequestCase.RESPONSE_HEADERS);
    hm = response.getResponseHeaders().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 0);
    assertEquals(hm.getRemoveHeadersCount(), 2);

    response = ctx.getResponse(RequestCase.RESPONSE_BODY);
    hm = response.getResponseBody().getResponse().getHeaderMutation();
    assertEquals(hm.getSetHeadersCount(), 0);
    assertEquals(hm.getRemoveHeadersCount(), 2);
  }

  @Test
  void phaseAccessorsDefaultToUnspecifiedPhase() {
    RequestContext ctx = new RequestContext();
    assertEquals(RequestCase.REQUEST_NOT_SET, ctx.getPhase());
    assertEquals("UNDETERMINED", ctx.getPhaseName());
    assertEquals(0, ctx.getPhaseNumber());
  }

  @Test
  void phaseAccessorsReflectCurrentPhase() {
    RequestContext ctx = new RequestContext();
    ctx.reset(RequestCase.REQUEST_HEADERS);
    assertEquals(RequestCase.REQUEST_HEADERS, ctx.getPhase());
    assertEquals("REQUEST_HEADERS", ctx.getPhaseName());
    assertEquals(1, ctx.getPhaseNumber());

    ctx.reset(RequestCase.REQUEST_BODY);
    assertEquals(RequestCase.REQUEST_BODY, ctx.getPhase());
    assertEquals("REQUEST_BODY", ctx.getPhaseName());
    assertEquals(2, ctx.getPhaseNumber());

    ctx.reset(RequestCase.REQUEST_TRAILERS);
    assertEquals(RequestCase.REQUEST_TRAILERS, ctx.getPhase());
    assertEquals("REQUEST_TRAILERS", ctx.getPhaseName());
    assertEquals(3, ctx.getPhaseNumber());

    ctx.reset(RequestCase.RESPONSE_HEADERS);
    assertEquals(RequestCase.RESPONSE_HEADERS, ctx.getPhase());
    assertEquals("RESPONSE_HEADERS", ctx.getPhaseName());
    assertEquals(4, ctx.getPhaseNumber());

    ctx.reset(RequestCase.RESPONSE_BODY);
    assertEquals(RequestCase.RESPONSE_BODY, ctx.getPhase());
    assertEquals("RESPONSE_BODY", ctx.getPhaseName());
    assertEquals(5, ctx.getPhaseNumber());

    ctx.reset(RequestCase.RESPONSE_TRAILERS);
    assertEquals(RequestCase.RESPONSE_TRAILERS, ctx.getPhase());
    assertEquals("RESPONSE_TRAILERS", ctx.getPhaseName());
    assertEquals(6, ctx.getPhaseNumber());
  }

  @Test
  void durationAccumulatesAcrossPhases() {
    RequestContext ctx = new RequestContext();
    assertEquals(0, ctx.getDuration().toNanos());

    ctx.updateDuration(RequestCase.REQUEST_HEADERS, Duration.ofNanos(100));
    assertEquals(100, ctx.getDuration().toNanos());

    ctx.updateDuration(RequestCase.REQUEST_BODY, Duration.ofNanos(50));
    assertEquals(150, ctx.getDuration().toNanos());
  }

  @Test
  void resetClearsArrays() {
    RequestContext ctx = new RequestContext();
    assertEquals(ctx.addHeaders.size(), 0);
    assertEquals(ctx.removeHeaders.size(), 0);

    ctx.addHeader("header1", "value");
    ctx.addHeader("header2", "value");
    ctx.removeHeader("header3");
    assertEquals(ctx.addHeaders.size(), 2);
    assertEquals(ctx.removeHeaders.size(), 1);

    ctx.reset(RequestCase.REQUEST_BODY);
    assertEquals(ctx.addHeaders.size(), 0);
    assertEquals(ctx.removeHeaders.size(), 0);
  }

  @Test
  void replaceBodyStringTest() {
    String replacedBody = "body";
    RequestContext ctx = new RequestContext();
    ctx.replaceBodyChunk(replacedBody);
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    ByteString body = response.getRequestBody().getResponse().getBodyMutation().getBody();
    assertEquals(body, ByteString.copyFromUtf8(replacedBody));
  }

  @Test
  void replaceBodyBytesTest() {
    String replacedBody = "body";
    RequestContext ctx = new RequestContext();
    ctx.replaceBodyChunk(replacedBody.getBytes());
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    ByteString body = response.getRequestBody().getResponse().getBodyMutation().getBody();
    assertEquals(body, ByteString.copyFromUtf8(replacedBody));
  }

  @Test
  void clearBodyTest() {
    RequestContext ctx = new RequestContext();
    ctx.clearBodyChunk();
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertTrue(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
  }

  @Test
  void replaceBodyReplacesWholeBodyAndUpdatesContentLengthInHeaderPhases() {
    RequestContext ctx = new RequestContext();
    ctx.reset(RequestCase.REQUEST_HEADERS);
    ctx.replaceBody("hello".getBytes(StandardCharsets.UTF_8));
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertTrue(response.getRequestHeaders().getResponse().hasBodyMutation());
    assertTrue(response.getRequestHeaders().getResponse().getBodyMutation().hasBody());
    assertFalse(response.getRequestHeaders().getResponse().getBodyMutation().getClearBody());
    assertEquals(
        ByteString.copyFromUtf8("hello"),
        response.getRequestHeaders().getResponse().getBodyMutation().getBody());
    assertEquals(1, ctx.addHeaders.size());
    assertEquals("content-length", ctx.addHeaders.get(0).getHeader().getKey());
    assertEquals("", ctx.addHeaders.get(0).getHeader().getValue());
    assertEquals("5", ctx.addHeaders.get(0).getHeader().getRawValue().toStringUtf8());
  }

  @Test
  void replaceBodyDoesNotUpdateContentLengthInBodyPhases() {
    RequestContext ctx = new RequestContext();
    ctx.reset(RequestCase.REQUEST_BODY);
    ctx.replaceBody("hello".getBytes(StandardCharsets.UTF_8));
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertTrue(response.getRequestBody().getResponse().hasBodyMutation());
    assertEquals(
        ByteString.copyFromUtf8("hello"),
        response.getRequestBody().getResponse().getBodyMutation().getBody());
    assertEquals(0, ctx.addHeaders.size());
    assertFalse(response.getRequestBody().getResponse().hasHeaderMutation());

    ctx.reset(RequestCase.RESPONSE_BODY);
    ctx.replaceBody("world");
    response = ctx.getResponse(RequestCase.RESPONSE_BODY);
    assertTrue(response.getResponseBody().getResponse().hasBodyMutation());
    assertEquals(
        ByteString.copyFromUtf8("world"),
        response.getResponseBody().getResponse().getBodyMutation().getBody());
    assertEquals(0, ctx.addHeaders.size());
    assertFalse(response.getResponseBody().getResponse().hasHeaderMutation());
  }

  @Test
  void clearBodyClearsWholeBodyAndZeroesContentLengthOnlyInHeaderPhases() {
    RequestContext ctx = new RequestContext();
    ctx.reset(RequestCase.RESPONSE_HEADERS);
    ctx.clearBody();
    ProcessingResponse response = ctx.getResponse(RequestCase.RESPONSE_HEADERS);
    assertTrue(response.getResponseHeaders().getResponse().hasBodyMutation());
    assertTrue(response.getResponseHeaders().getResponse().getBodyMutation().getClearBody());
    assertFalse(response.getResponseHeaders().getResponse().getBodyMutation().hasBody());
    assertEquals(1, ctx.addHeaders.size());
    assertEquals("content-length", ctx.addHeaders.get(0).getHeader().getKey());
    assertEquals("0", ctx.addHeaders.get(0).getHeader().getRawValue().toStringUtf8());

    ctx.reset(RequestCase.RESPONSE_BODY);
    ctx.clearBody();
    response = ctx.getResponse(RequestCase.RESPONSE_BODY);
    assertTrue(response.getResponseBody().getResponse().getBodyMutation().getClearBody());
    assertEquals(0, ctx.addHeaders.size());
    assertFalse(response.getResponseBody().getResponse().hasHeaderMutation());
  }

  @Test
  void emptyReplaceBodyClearsBodyAndNullIsNoOp() {
    RequestContext ctx = new RequestContext();
    ctx.reset(RequestCase.REQUEST_BODY);
    ctx.replaceBody(new byte[0]);
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertTrue(response.getRequestBody().getResponse().hasBodyMutation());
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().hasBody());
    assertTrue(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    assertEquals(0, ctx.addHeaders.size());

    ctx.reset(RequestCase.REQUEST_HEADERS);
    ctx.replaceBody("");
    response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertTrue(response.getRequestHeaders().getResponse().getBodyMutation().getClearBody());
    assertEquals(1, ctx.addHeaders.size());
    assertEquals("content-length", ctx.addHeaders.get(0).getHeader().getKey());
    assertEquals("0", ctx.addHeaders.get(0).getHeader().getRawValue().toStringUtf8());

    ctx.reset(RequestCase.REQUEST_BODY);
    ctx.replaceBody((byte[]) null);
    ctx.replaceBody((String) null);
    response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertFalse(response.getRequestBody().getResponse().hasBodyMutation());
    assertFalse(response.getRequestBody().getResponse().hasHeaderMutation());
  }

  @Test
  void updateAndRemoveHeaderLowercaseKeysAndUseRawValue() {
    RequestContext ctx = new RequestContext();
    ctx.addHeader("X-Custom-Header", "Val-1");
    ctx.appendHeader("X-Another", "Val-2");
    ctx.overwriteHeader("X-Overwrite", "Val-3");
    ctx.removeHeader("X-Remove-Me");

    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    HeaderMutation hm = response.getRequestHeaders().getResponse().getHeaderMutation();
    assertEquals(3, hm.getSetHeadersCount());
    assertEquals("x-custom-header", hm.getSetHeaders(0).getHeader().getKey());
    assertEquals("", hm.getSetHeaders(0).getHeader().getValue());
    assertEquals(ByteString.copyFromUtf8("Val-1"), hm.getSetHeaders(0).getHeader().getRawValue());
    assertEquals("x-another", hm.getSetHeaders(1).getHeader().getKey());
    assertEquals("Val-2", hm.getSetHeaders(1).getHeader().getRawValue().toStringUtf8());
    assertEquals("x-overwrite", hm.getSetHeaders(2).getHeader().getKey());
    assertEquals("Val-3", hm.getSetHeaders(2).getHeader().getRawValue().toStringUtf8());
    assertEquals(1, hm.getRemoveHeadersCount());
    assertEquals("x-remove-me", hm.getRemoveHeaders(0));
  }

  @Test
  void responsesOmitEmptyHeaderAndBodyMutations() {
    RequestContext ctx = new RequestContext();
    ProcessingResponse reqHeaders = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertFalse(reqHeaders.getRequestHeaders().getResponse().hasHeaderMutation());
    assertFalse(reqHeaders.getRequestHeaders().getResponse().hasBodyMutation());

    ProcessingResponse reqBody = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertFalse(reqBody.getRequestBody().getResponse().hasHeaderMutation());
    assertFalse(reqBody.getRequestBody().getResponse().hasBodyMutation());

    ProcessingResponse respHeaders = ctx.getResponse(RequestCase.RESPONSE_HEADERS);
    assertFalse(respHeaders.getResponseHeaders().getResponse().hasHeaderMutation());
    assertFalse(respHeaders.getResponseHeaders().getResponse().hasBodyMutation());

    ProcessingResponse respBody = ctx.getResponse(RequestCase.RESPONSE_BODY);
    assertFalse(respBody.getResponseBody().getResponse().hasHeaderMutation());
    assertFalse(respBody.getResponseBody().getResponse().hasBodyMutation());

    ProcessingResponse reqTrailers = ctx.getResponse(RequestCase.REQUEST_TRAILERS);
    assertFalse(reqTrailers.getRequestTrailers().hasHeaderMutation());

    ProcessingResponse respTrailers = ctx.getResponse(RequestCase.RESPONSE_TRAILERS);
    assertFalse(respTrailers.getResponseTrailers().hasHeaderMutation());
  }

  @Test
  void setProcessorIdSetsOnceWhenPreviouslyNull() {
    RequestContext ctx = new RequestContext();
    assertNull(ctx.getProcessorId());
    ctx.setProcessorId("proc-1");
    assertEquals("proc-1", ctx.getProcessorId());
    ctx.setProcessorId("proc-2");
    assertEquals("proc-1", ctx.getProcessorId());
  }

  @Test
  void initializeRequestKeepsRequestIdInRequestHeaders() {
    RequestContext ctx = new RequestContext();
    HeaderMap headerMap =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue("GET"))
            .addHeaders(HeaderValue.newBuilder().setKey("x-request-id").setValue("req-123"))
            .addHeaders(HeaderValue.newBuilder().setKey("x-other").setValue("abc"))
            .build();
    ctx.initializeRequest(headerMap);
    assertEquals("req-123", ctx.getRequestId());
    assertEquals("req-123", ctx.getRequestHeader("x-request-id"));
    assertEquals("req-123", ctx.getAllRequestHeaders().get("x-request-id"));
    assertEquals("abc", ctx.getRequestHeader("x-other"));
  }

  @Test
  void cancelRequestHandlesInvalidStatusAndIsolatesHeaders() {
    RequestContext ctx = new RequestContext();
    ctx.addHeader("x-prior", "should-not-leak");
    ctx.removeHeader("x-prior-remove");

    Map<String, String> cancelHeaders = new HashMap<String, String>();
    cancelHeaders.put("X-Cancel-Reason", "denied");
    ctx.cancelRequest(0, cancelHeaders, "bad");
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertEquals(StatusCode.BadRequest, response.getImmediateResponse().getStatus().getCode());
    assertEquals("bad", response.getImmediateResponse().getBody());
    HeaderMutation hm = response.getImmediateResponse().getHeaders();
    assertEquals(1, hm.getSetHeadersCount());
    assertEquals(0, hm.getRemoveHeadersCount());
    assertEquals("x-cancel-reason", hm.getSetHeaders(0).getHeader().getKey());
    assertEquals("", hm.getSetHeaders(0).getHeader().getValue());
    assertEquals("denied", hm.getSetHeaders(0).getHeader().getRawValue().toStringUtf8());

    RequestContext unknownStatusCtx = new RequestContext();
    unknownStatusCtx.cancelRequest(999, Collections.<String, String>emptyMap(), "unknown");
    ProcessingResponse unknownResp = unknownStatusCtx.getResponse(RequestCase.REQUEST_HEADERS);
    assertEquals(
        StatusCode.InternalServerError, unknownResp.getImmediateResponse().getStatus().getCode());
    assertFalse(unknownResp.getImmediateResponse().hasHeaders());
  }

  @Test
  void allRequestHeadersPreserveMultiValuesInWireOrder() {
    RequestContext ctx = new RequestContext();
    ctx.getAllRequestHeaders().add("x-multi", "a");
    ctx.getAllRequestHeaders().add("x-multi", "b");
    assertEquals(2, ctx.getAllRequestHeaders().values("x-multi").size());
    assertEquals("a", ctx.getAllRequestHeaders().get("x-multi"));
    assertEquals(0, ctx.getAllRequestHeaders().rawValues("x-multi").size());
    assertTrue(ctx.getAllRequestHeaders().has("x-multi"));
  }

  @Test
  void allResponseHeadersPopulateFromInit() {
    RequestContext ctx = new RequestContext();
    HeaderMap headerMap =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey("x-multi").setValue("a"))
            .addHeaders(HeaderValue.newBuilder().setKey("x-multi").setValue("b"))
            .build();
    ctx.initializeResponse(headerMap);
    assertEquals(2, ctx.getAllResponseHeaders().values("x-multi").size());
  }

  @Test
  void clearRouteCacheIsSurfacedInResponse() {
    RequestContext ctx = new RequestContext();
    ctx.clearRouteCache();
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertTrue(response.getRequestHeaders().getResponse().getClearRouteCache());
  }

  @Test
  void unClearRouteCacheClearsFlag() {
    RequestContext ctx = new RequestContext();
    ctx.clearRouteCache();
    assertTrue(ctx.isClearRouteCache());
    ctx.unClearRouteCache();
    assertFalse(ctx.isClearRouteCache());
  }

  @Test
  void resetClearsClearRouteCache() {
    RequestContext ctx = new RequestContext();
    ctx.clearRouteCache();
    assertTrue(ctx.isClearRouteCache());
    ctx.reset(RequestCase.REQUEST_BODY);
    assertFalse(ctx.isClearRouteCache());
  }

  @Test
  void cancellingReturnsImmediateResponse() {
    RequestContext ctx = new RequestContext();
    ctx.cancelRequest(200, null, "OK");
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertNotNull(response.getImmediateResponse());
    assertEquals(response.getImmediateResponse().getStatus().getCodeValue(), 200);
    assertEquals(response.getImmediateResponse().getBody(), "OK");
  }

  @Test
  void initializeResponseDoesNotError() throws Exception {
    RequestContext ctx = new RequestContext();
    ctx.initializeResponse(null);
    assertEquals(ctx.getStatus(), 0);

    HeaderMap headerMap =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue(""))
            .addHeaders(
                HeaderValue.newBuilder().setKey("content-type").setValue("application/json"))
            .build();
    ctx.initializeResponse(headerMap);
    assertEquals(ctx.getStatus(), 0);

    headerMap =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("nan"))
            .addHeaders(
                HeaderValue.newBuilder().setKey("content-type").setValue("application/json"))
            .build();
    ctx.initializeResponse(headerMap);
    assertEquals(ctx.getStatus(), 0);

    headerMap =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("200"))
            .addHeaders(
                HeaderValue.newBuilder().setKey("content-type").setValue("application/json"))
            .build();
    ctx.initializeResponse(headerMap);
    assertEquals(ctx.getStatus(), 200);
  }

  @Test
  void initializeResponseDoesHandlesRawValue() throws Exception {
    RequestContext ctx = new RequestContext();

    HeaderMap headerMap =
        HeaderMap.newBuilder()
            .addHeaders(
                HeaderValue.newBuilder()
                    .setKey(":status")
                    .setRawValue(ByteString.copyFrom("200".getBytes(StandardCharsets.UTF_8))))
            .addHeaders(
                HeaderValue.newBuilder()
                    .setKey("content-type")
                    .setRawValue(
                        ByteString.copyFrom("application/json".getBytes(StandardCharsets.UTF_8))))
            .build();
    ctx.initializeResponse(headerMap);
    assertEquals(ctx.getStatus(), 200);
    assertEquals(ctx.getResponseHeader("content-type"), "application/json");
  }

  @Test
  void customDataSetAndGet() {
    RequestContext ctx = new RequestContext();
    assertNull(ctx.getValue("customId"));
    ctx.setValue("customId", "abc-123");
    assertEquals("abc-123", ctx.getValue("customId"));
    assertEquals("abc-123", ctx.getValue("customId", String.class));
  }

  @Test
  void customDataTypedGet() {
    RequestContext ctx = new RequestContext();
    java.util.UUID uuid = java.util.UUID.randomUUID();
    ctx.setValue("customId", uuid);
    assertEquals(uuid, ctx.getValue("customId", java.util.UUID.class));
  }

  @Test
  void customDataPersistsAcrossPhaseResets() {
    RequestContext ctx = new RequestContext();
    ctx.setValue("routed", Boolean.FALSE);
    ctx.reset(RequestCase.REQUEST_BODY);
    assertEquals(Boolean.FALSE, ctx.getValue("routed", Boolean.class));
  }
}
