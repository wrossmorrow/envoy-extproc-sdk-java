package extproc.processors;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;
import extproc.RequestProcessorHealthManager;
import java.util.Map;

public class NoOpRequestProcessor implements RequestProcessor {
  public String getName() {
    return "NoOpRequestProcessor";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {}

  public void processRequestBody(RequestContext ctx, String body) {}

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
