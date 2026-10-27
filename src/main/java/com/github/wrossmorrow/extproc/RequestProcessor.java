package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
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

  /**
   * Called when an error occurs while processing a phase. The phase is the phase that was being
   * processed when the error occurred; stream cancellations (client going away mid-stream, envoy
   * aborting) are not errors and still reach this handler so implementations can clean up, but they
   * are not phase processing failures and must not be treated as errors by the server.
   *
   * <p>Implementations may perform any cleanup (flush, commit, etc.) here. The default
   * implementation does nothing.
   */
  default void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {}

  /**
   * Called on server/JVM shutdown, after in-flight streams have drained, to facilitate any cleanup.
   * Implementations may want to flush or commit here. The default implementation delegates to
   * {@link #shutdown()}.
   *
   * @param gracePeriodSeconds the termination grace period, in seconds
   */
  default void close(int gracePeriodSeconds) {
    shutdown();
  }

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
}
