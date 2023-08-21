package extproc.processors;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;
import extproc.RequestProcessorHealthManager;
import java.util.Map;

public class TrivialRequestProcessor implements RequestProcessor {

  public String getName() {
    return "TrivialRequestProcessor";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.addHeader("x-extproc-request-seen", "true");
    ctx.continueRequest();
  }

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    ctx.addHeader("x-extproc-response-seen", "true");
    ctx.continueRequest();
  }

  public void processResponseBody(RequestContext ctx, String body) {
    ctx.addHeader("x-extproc-response-seen", "true");
    ctx.continueRequest();
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
