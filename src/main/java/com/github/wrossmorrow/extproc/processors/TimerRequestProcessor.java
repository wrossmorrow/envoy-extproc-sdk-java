package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

public class TimerRequestProcessor implements RequestProcessor {

  public String getName() {
    return "timer";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    final Instant started = ctx.getStarted();
    ctx.addHeader("x-extproc-started", started.toString());
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.streamComplete()) {
      processComplete(ctx);
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (ctx.streamComplete()) {
      processComplete(ctx);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

  protected void processComplete(RequestContext ctx) {
    final Instant started = ctx.getStarted();
    final Instant finished = Instant.now();
    final Duration duration = Duration.between(started, finished);
    ctx.addHeader("x-extproc-started", started.toString());
    ctx.addHeader("x-extproc-finished", finished.toString());
    ctx.addHeader("x-extproc-upstream-duration-ns", String.valueOf(duration.toNanos()));
  }
}
