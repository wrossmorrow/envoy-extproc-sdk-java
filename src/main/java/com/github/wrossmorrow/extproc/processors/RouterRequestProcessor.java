package com.github.wrossmorrow.extproc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;

public class RouterRequestProcessor implements RequestProcessor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  public String getName() {
    return "router";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.setValue("routed", Boolean.FALSE);
    String upstream = ctx.getAllRequestHeaders().get("x-route-to-upstream");
    if (upstream != null) {
      ctx.setValue("routed", Boolean.TRUE);
      ctx.overwriteHeader(":authority", upstream);
      ctx.clearRouteCache();
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    Boolean routed = ctx.getValue("routed", Boolean.class);
    if (routed != null && routed) {
      // already routed via the request header
      return;
    }
    if (body == null) {
      return;
    }
    try {
      JsonNode parsedBody = MAPPER.readTree(body);
      JsonNode upstream = parsedBody.get("upstream");
      if (upstream != null) {
        ctx.setValue("routed", Boolean.TRUE);
        ctx.overwriteHeader(":authority", upstream.asText());
        ctx.clearRouteCache();
      }
    } catch (Exception e) {
      // leave the request unrouted if the body is not parseable JSON
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
