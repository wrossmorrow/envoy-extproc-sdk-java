package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.HashMap;
import java.util.Map;

public class DedupRequestProcessor implements RequestProcessor {

  Map<String, String> inflight;

  public DedupRequestProcessor() {
    inflight = new HashMap<String, String>();
  }

  public String getName() {
    return "dedup";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.getRequestHeaders().containsKey("x-extproc-request-digest")) {
      String digest = ctx.getRequestHeaders().get("x-extproc-request-digest");
      if (inflight.containsKey(digest)) {
        String requestId = inflight.get(digest);
        ctx.cancelRequest(
            409,
            "{\"message\":\"Duplicate request already in flight\",\"requestId\":\""
                + requestId
                + "\"}");
        return;
      }
      inflight.put(digest, ctx.getRequestId());
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.streamComplete()) {
      String digest = ctx.getRequestHeaders().get("x-extproc-request-digest");
      inflight.remove(digest);
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (ctx.streamComplete()) {
      String digest = ctx.getRequestHeaders().get("x-extproc-request-digest");
      inflight.remove(digest);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
