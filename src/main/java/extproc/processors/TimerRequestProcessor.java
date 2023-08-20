package extproc.processors;

import java.time.Instant;
import java.time.Duration;
import java.util.Map;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;

public class TimerRequestProcessor implements RequestProcessor {

    public String getName() { return "TimerRequestProcessor"; }
    public ProcessingOptions getOptions() { return new ProcessingOptions(); }

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
        final Instant started = ctx.getStarted();
        ctx.addHeader("x-extproc-started", started.toString());
        ctx.continueRequest();
    }

    public void processRequestBody(RequestContext ctx, String body) {}

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
        final Instant started = ctx.getStarted();
        final Instant finished = Instant.now();
        final Duration duration = Duration.between(started, finished);
        ctx.addHeader("x-extproc-started", started.toString());
        ctx.addHeader("x-extproc-finished", finished.toString());
        ctx.addHeader("x-upstream-duration-ns", String.valueOf(duration.toNanos()));
        ctx.continueRequest();
    }

    public void processResponseBody(RequestContext ctx, String body) {
        final Instant started = ctx.getStarted();
        final Instant finished = Instant.now();
        final Duration duration = Duration.between(started, finished);
        ctx.addHeader("x-extproc-started", started.toString());
        ctx.addHeader("x-extproc-finished", finished.toString());
        ctx.addHeader("x-upstream-duration-ns", String.valueOf(duration.toNanos()));
        ctx.continueRequest();
    }
    
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

}
