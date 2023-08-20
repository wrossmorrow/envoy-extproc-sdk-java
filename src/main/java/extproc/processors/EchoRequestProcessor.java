package extproc.processors;

import java.util.Map;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;

public class EchoRequestProcessor implements RequestProcessor {
    public String getName() { return "EchoRequestProcessor"; }
    public ProcessingOptions getOptions() { return new ProcessingOptions(); }

    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
        if (ctx.streamComplete()) {
            ctx.cancelRequest(200, ctx.getRequestHeaders());
        }
    }

    public void processRequestBody(RequestContext ctx, String body) {
        ctx.cancelRequest(200, ctx.getRequestHeaders(), body);
    }

    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}
    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}
    public void processResponseBody(RequestContext ctx, String body) {}
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
