package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;
import java.util.UUID;

public class DataRequestProcessor implements RequestProcessor {

  public String getName() {
    return "data";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.setValue("customId", UUID.randomUUID());
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    UUID customId = ctx.getValue("customId", UUID.class);
    if (customId != null) {
      ctx.addHeader("x-extproc-custom-data", customId.toString());
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
