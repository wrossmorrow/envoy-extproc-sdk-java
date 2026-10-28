package com.github.wrossmorrow.extproc.processors;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.List;
import java.util.Map;
import java.util.logging.Level;
import java.util.logging.Logger;

/**
 * Request processor used by the testing harness. Mirrors the Go and Rust SDKs' tester processors,
 * which drive full ext_proc exchanges (via the load generator) and mutate the stream through the
 * processing actions encoded in the request body.
 */
public class TestingRequestProcessor implements RequestProcessor {

  private static final Logger logger = Logger.getLogger(TestingRequestProcessor.class.getName());

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /** Request processing actions applied by the tester across the phases of a single exchange. */
  public static final class TesterRequestBody {

    @JsonProperty("append_request_headers")
    public Map<String, String> appendRequestHeaders;

    @JsonProperty("add_request_headers")
    public Map<String, String> addRequestHeaders;

    @JsonProperty("overwrite_request_headers")
    public Map<String, String> overwriteRequestHeaders;

    @JsonProperty("remove_request_headers")
    public List<String> removeRequestHeaders;

    @JsonProperty("clear_request_body")
    public boolean clearRequestBody;

    @JsonProperty("replace_request_body")
    public String replaceRequestBody;

    @JsonProperty("append_response_headers")
    public Map<String, String> appendResponseHeaders;

    @JsonProperty("add_response_headers")
    public Map<String, String> addResponseHeaders;

    @JsonProperty("overwrite_response_headers")
    public Map<String, String> overwriteResponseHeaders;

    @JsonProperty("remove_response_headers")
    public List<String> removeResponseHeaders;

    @JsonProperty("clear_response_body")
    public boolean clearResponseBody;

    @JsonProperty("replace_response_body")
    public String replaceResponseBody;

    @JsonProperty("cancel_request")
    public boolean cancelRequest;

    @JsonProperty("cancel_request_status")
    public int cancelRequestStatus;

    @JsonProperty("cancel_request_headers")
    public Map<String, String> cancelRequestHeaders;

    @JsonProperty("cancel_request_body")
    public String cancelRequestBody;
  }

  private final ProcessingOptions options;
  private volatile TesterRequestBody actions;

  public TestingRequestProcessor() {
    this(new ProcessingOptions());
  }

  public TestingRequestProcessor(ProcessingOptions options) {
    this.options = options;
  }

  @Override
  public String getName() {
    return "testing";
  }

  @Override
  public ProcessingOptions getOptions() {
    return options;
  }

  @Override
  public void setHealthManager(RequestProcessorHealthManager health) {}

  @Override
  public void shutdown() {
    logger.info(TestingRequestProcessor.class.getSimpleName() + " shutting down");
  }

  @Override
  public void close(int gracePeriodSeconds) {
    logger.info("Closing tester");
  }

  @Override
  public void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {
    logger.log(Level.INFO, "Error in phase {0}: {1}", new Object[] {phase, err});
  }

  @Override
  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    reportHeaderParsing(ctx, headers);

    // optional upstream override, exercising clear-route-cache driven upstream
    // changes: rewrite the authority and clear the route cache so envoy
    // recomputes the route
    String upstream = getHeaderIgnoreCase(headers, "x-route-to-upstream");
    if (upstream != null) {
      ctx.overwriteHeader(":authority", upstream);
      ctx.clearRouteCache();
    }

    ctx.continueAndReplace();
  }

  @Override
  public void processRequestBody(RequestContext ctx, String body) {
    // NOTE: assuming buffering, not streaming; if buffering we have to
    // accumulate the request body chunks sent until the end of stream

    TesterRequestBody parsed = parseActions(body);
    ctx.setValue("actions", parsed);

    if (parsed != null && parsed.cancelRequest) {
      ctx.cancelRequest(
          parsed.cancelRequestStatus, parsed.cancelRequestHeaders, parsed.cancelRequestBody);
      return;
    }

    if (parsed != null) {
      applyRequestHeaderActions(ctx, parsed);
      applyRequestBodyActions(ctx, parsed);
    }

    ctx.continueAndReplace();
  }

  @Override
  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
    ctx.continueAndReplace();
  }

  @Override
  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    TesterRequestBody parsed = (TesterRequestBody) ctx.getValue("actions");
    if (parsed != null) {
      applyResponseHeaderActions(ctx, parsed);
    }
    ctx.continueAndReplace();
  }

  @Override
  public void processResponseBody(RequestContext ctx, String body) {
    TesterRequestBody parsed = (TesterRequestBody) ctx.getValue("actions");
    if (parsed != null) {
      applyResponseBodyActions(ctx, parsed);
    }
    ctx.continueAndReplace();
  }

  @Override
  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    ctx.continueAndReplace();
  }

  /** Reflect on how the SDK parsed the inbound headers back via the request headers. */
  protected void reportHeaderParsing(RequestContext ctx, Map<String, String> headers) {
    ctx.addHeader("x-parsed-str-count", String.valueOf(ctx.getAllRequestHeaders().size()));
    String first = ctx.getAllRequestHeaders().get("x-probe-list");
    ctx.addHeader("x-parsed-list-value", first == null ? "" : first);
    List<String> values = ctx.getAllRequestHeaders().values("x-probe-list");
    ctx.addHeader("x-parsed-list-count", String.valueOf(values.size()));
    List<String> split = ctx.getAllRequestHeaders().splitList("x-probe-list");
    ctx.addHeader("x-parsed-list-split", String.join("|", split));
  }

  protected TesterRequestBody parseActions(String body) {
    if (body == null || body.isEmpty()) {
      return null;
    }
    try {
      JsonNode node = MAPPER.readTree(body);
      if (node == null || node.isNull() || !node.isObject()) {
        return null;
      }
      TesterRequestBody actions = MAPPER.convertValue(node, TesterRequestBody.class);
      return actions;
    } catch (Exception e) {
      logger.log(Level.WARNING, "Error parsing request body: {0}", e.getMessage());
      return null;
    }
  }

  protected void applyRequestHeaderActions(RequestContext ctx, TesterRequestBody actions) {
    if (actions.appendRequestHeaders != null) {
      ctx.appendHeaders(actions.appendRequestHeaders);
    }
    if (actions.addRequestHeaders != null) {
      ctx.addHeaders(actions.addRequestHeaders);
    }
    if (actions.overwriteRequestHeaders != null) {
      ctx.overwriteHeaders(actions.overwriteRequestHeaders);
    }
    if (actions.removeRequestHeaders != null) {
      ctx.removeHeaders(actions.removeRequestHeaders);
    }
  }

  protected void applyResponseHeaderActions(RequestContext ctx, TesterRequestBody actions) {
    if (actions.appendResponseHeaders != null) {
      ctx.appendHeaders(actions.appendResponseHeaders);
    }
    if (actions.addResponseHeaders != null) {
      ctx.addHeaders(actions.addResponseHeaders);
    }
    if (actions.overwriteResponseHeaders != null) {
      ctx.overwriteHeaders(actions.overwriteResponseHeaders);
    }
    if (actions.removeResponseHeaders != null) {
      ctx.removeHeaders(actions.removeResponseHeaders);
    }
  }

  protected void applyRequestBodyActions(RequestContext ctx, TesterRequestBody actions) {
    if (actions.clearRequestBody) {
      ctx.clearBody();
    } else if (actions.replaceRequestBody != null) {
      ctx.replaceBody(actions.replaceRequestBody);
    }
  }

  protected void applyResponseBodyActions(RequestContext ctx, TesterRequestBody actions) {
    if (actions.clearResponseBody) {
      ctx.clearBody();
    } else if (actions.replaceResponseBody != null) {
      ctx.replaceBody(actions.replaceResponseBody);
    }
  }

  /** Case-insensitive header lookup over the phase header map. */
  protected String getHeaderIgnoreCase(Map<String, String> headers, String name) {
    if (headers == null) {
      return null;
    }
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name)) {
        return entry.getValue();
      }
    }
    return null;
  }
}
