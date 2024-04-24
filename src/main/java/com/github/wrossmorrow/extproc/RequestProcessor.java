package com.github.wrossmorrow.extproc;

import java.util.Map;

public interface RequestProcessor {

  /** Supplies a name for this processor */
  public String getName();

  /** Supplies options for this processor, as read/set in the customized processor */
  public ProcessingOptions getOptions();

  /** Provides a "health manager" for the customized processor to declare state */
  public void setHealthManager(RequestProcessorHealthManager health);

  /** Called on server/JVM shutdown to facilitate any cleanup */
  public void shutdown();

  /** Method for processing request headers */
  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers);

  /** Method for processing request body (chunks) */
  public void processRequestBody(RequestContext ctx, String body);

  /** Method for processing request trailers */
  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers);

  /** Method for processing response headers */
  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers);

  /** Method for processing response body (chunks) */
  public void processResponseBody(RequestContext ctx, String body);

  /** Method for processing response trailers */
  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers);

  /** Allow a customized processor to tell the server to complete the stream */
  public boolean processingComplete(RequestContext ctx);
}
