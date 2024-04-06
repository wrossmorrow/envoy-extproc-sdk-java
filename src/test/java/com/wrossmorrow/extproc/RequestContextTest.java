package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.ByteString;
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
    ctx.reset();
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
  void resetClearsArrays() {
    RequestContext ctx = new RequestContext();
    assertEquals(ctx.addHeaders.size(), 0);
    assertEquals(ctx.removeHeaders.size(), 0);

    ctx.addHeader("header1", "value");
    ctx.addHeader("header2", "value");
    ctx.removeHeader("header3");
    assertEquals(ctx.addHeaders.size(), 2);
    assertEquals(ctx.removeHeaders.size(), 1);

    ctx.reset();
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
  void cancellingReturnsImmediateResponse() {
    RequestContext ctx = new RequestContext();
    ctx.cancelRequest(200, null, "OK");
    ProcessingResponse response = ctx.getResponse(RequestCase.REQUEST_HEADERS);
    assertNotNull(response.getImmediateResponse());
    assertEquals(response.getImmediateResponse().getStatus().getCodeValue(), 200);
    assertEquals(response.getImmediateResponse().getBody(), "OK");
  }
}
