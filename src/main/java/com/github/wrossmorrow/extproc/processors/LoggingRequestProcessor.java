package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;

public class LoggingRequestProcessor implements RequestProcessor {
  public String getName() {
    return "noop";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    System.out.println(body);
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
    for (Map.Entry<String, String> entry : trailers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    System.out.println(body);
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    for (Map.Entry<String, String> entry : trailers.entrySet()) {
      System.out.println(entry.getKey() + ": " + entry.getValue());
    }
  }

  public boolean processingComplete(RequestContext ctx) {
    return false;
  }
}
