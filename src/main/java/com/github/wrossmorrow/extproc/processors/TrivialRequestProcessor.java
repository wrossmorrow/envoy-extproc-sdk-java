package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;

public class TrivialRequestProcessor implements RequestProcessor {

  public String getName() {
    return "trivial";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.addHeader("x-extproc-request-seen", "true");
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.addHeader("x-extproc-response-seen", "true");
  }

  public void processResponseBody(RequestContext ctx, String body) {
    ctx.addHeader("x-extproc-response-seen", "true");
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
