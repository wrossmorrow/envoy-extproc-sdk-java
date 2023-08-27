package com.wrossmorrow.extproc.processors;

import com.wrossmorrow.extproc.ProcessingOptions;
import com.wrossmorrow.extproc.RequestContext;
import com.wrossmorrow.extproc.RequestProcessor;
import com.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;

public class NoOpRequestProcessor implements RequestProcessor {
  public String getName() {
    return "noop";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {}

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
