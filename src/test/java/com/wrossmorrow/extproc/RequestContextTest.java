package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.ByteString;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
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
  void replaceBodyReplacesWholeBodyAndUpdatesContentLength() {
    RequestContext ctx = new RequestContext();
    ctx.replaceBody("hello".getBytes(java.nio.charset.StandardCharsets.UTF_8));
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertTrue(response.getRequestBody().getResponse().getBodyMutation().hasBody());
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    assertEquals(
        ByteString.copyFromUtf8("hello"),
        response.getRequestBody().getResponse().getBodyMutation().getBody());
    assertEquals(1, ctx.addHeaders.size());
    assertEquals("content-length", ctx.addHeaders.get(0).getHeader().getKey());
    assertEquals("5", ctx.addHeaders.get(0).getHeader().getValue());
  }

  @Test
  void clearBodyClearsWholeBodyAndZeroesContentLength() {
    RequestContext ctx = new RequestContext();
    ctx.clearBody();
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertTrue(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().hasBody());
    assertEquals(1, ctx.addHeaders.size());
    assertEquals("content-length", ctx.addHeaders.get(0).getHeader().getKey());
    assertEquals("0", ctx.addHeaders.get(0).getHeader().getValue());
  }

  @Test
  void emptyReplaceBodyIsNoOp() {
    RequestContext ctx = new RequestContext();
    ctx.replaceBody(new byte[0]);
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_BODY);
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().hasBody());
    assertFalse(response.getRequestBody().getResponse().getBodyMutation().getClearBody());
    assertEquals(0, ctx.addHeaders.size());
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
