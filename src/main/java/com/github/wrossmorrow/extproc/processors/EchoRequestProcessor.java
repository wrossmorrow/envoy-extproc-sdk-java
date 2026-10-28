package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;
import java.util.logging.Logger;
import java.util.regex.Pattern;

public class EchoRequestProcessor implements RequestProcessor {
  private static final Logger logger = Logger.getLogger(EchoRequestProcessor.class.getName());
  private static final Pattern ECHO_PATH = Pattern.compile("/echo/.*");

  public String getName() {
    return "echo";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ECHO_PATH.matcher(ctx.getPath()).matches()) {
      if (ctx.streamComplete()) {
        logger.info(
            "EchoRequestProcessor.processRequestHeaders: "
                + ctx.getPath()
                + " responding before upstream");
        ctx.cancelRequest(200, ctx.getRequestHeaders(), "");
      }
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (ECHO_PATH.matcher(ctx.getPath()).matches()) {
      logger.info(
          "EchoRequestProcessor.processRequestBody: "
              + ctx.getPath()
              + " responding before upstream");
      ctx.cancelRequest(200, ctx.getRequestHeaders(), body);
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {}

  public void processResponseBody(RequestContext ctx, String body) {}

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}
}
