package extproc.processors;

import extproc.ProcessingOptions;
import extproc.RequestContext;
import extproc.RequestProcessor;
import extproc.RequestProcessorHealthManager;
import java.util.Map;
import java.util.logging.Logger;

public class EchoRequestProcessor implements RequestProcessor {
  private static final Logger logger = Logger.getLogger(EchoRequestProcessor.class.getName());

  public String getName() {
    return "echo";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.getPath().startsWith("/echo")) {
      if (ctx.streamComplete()) {
        logger.info(
            "EchoRequestProcessor.processRequestHeaders: "
                + ctx.getPath()
                + " responding before upstream");
        String jsonResponse = "{\"path\": \"" + ctx.getPath() + "\"}";
        ctx.cancelRequest(200, ctx.getRequestHeaders(), jsonResponse);
      }
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (ctx.getPath().startsWith("/echo")) {
      logger.info(
          "EchoRequestProcessor.processRequestBody: "
              + ctx.getPath()
              + " responding before upstream");
      String jsonResponse = "{\"path\": \"" + ctx.getPath() + "\", \"body\": \"" + body + "\"}";
      ctx.cancelRequest(200, ctx.getRequestHeaders(), jsonResponse);
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
