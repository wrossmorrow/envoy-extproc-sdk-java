package com.github.wrossmorrow.extproc;

import java.util.Map;

public interface RequestProcessor {
  public String getName();

  public ProcessingOptions getOptions();

  public void setHealthManager(RequestProcessorHealthManager health);

  public void shutdown();

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers);

  public void processRequestBody(RequestContext ctx, String body);

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers);

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers);

  public void processResponseBody(RequestContext ctx, String body);

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers);
}
