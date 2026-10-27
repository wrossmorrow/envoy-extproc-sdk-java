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
    ctx.overwriteHeader("x-extproc-started-ns", epochNanos(ctx.getStarted()));
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.streamComplete()) {
      processComplete(ctx, false);
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (ctx.streamComplete()) {
      processComplete(ctx, true);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

  protected void processComplete(RequestContext ctx, boolean overwrite) {
    final Instant started = ctx.getStarted();
    final Instant finished = Instant.now();
    final Duration duration = Duration.between(started, finished);
    final String startedNs = epochNanos(started);
    final String finishedNs = epochNanos(finished);
    if (overwrite) {
      ctx.overwriteHeader("x-extproc-started-ns", startedNs);
      ctx.overwriteHeader("x-extproc-finished-ns", finishedNs);
      ctx.overwriteHeader("x-upstream-duration-ns", String.valueOf(duration.toNanos()));
    } else {
      ctx.addHeader("x-extproc-started-ns", startedNs);
      ctx.addHeader("x-extproc-finished-ns", finishedNs);
      ctx.addHeader("x-upstream-duration-ns", String.valueOf(duration.toNanos()));
    }
  }

  protected String epochNanos(Instant instant) {
    return String.valueOf(instant.getEpochSecond() * 1000000000L + instant.getNano());
  }
}
