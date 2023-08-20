package extproc.processors;

import java.util.Map;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;

public class NoOpRequestProcessor implements RequestProcessor {
    public String getName() { return "NoOpRequestProcessor"; }
    public ProcessingOptions getOptions() { return new ProcessingOptions(); }
    public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {}
    public void processRequestBody(RequestContext ctx, String body) {}
    public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}
    public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}
    public void processResponseBody(RequestContext ctx, String body) {}
    public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
