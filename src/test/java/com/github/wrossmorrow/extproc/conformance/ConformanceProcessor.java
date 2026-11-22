package com.github.wrossmorrow.extproc.conformance;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-process {@link RequestProcessor} implementing the ext_proc phases used by the conformance
 * harness, dispatching to a {@link CaseHandler} keyed by the {@code x-conformance-case} header
 * value (or context value for post-headers phases).
 */
public class ConformanceProcessor implements RequestProcessor {

  public static final String CASE_HEADER = "x-conformance-case";

  public interface CaseHandler {
    default void requestHeaders(RequestContext ctx, Map<String, String> headers) {}

    default void requestBody(RequestContext ctx, String body) {}

    default void requestTrailers(RequestContext ctx, Map<String, String> trailers) {}

    default void responseHeaders(RequestContext ctx, Map<String, String> headers) {}

    default void responseBody(RequestContext ctx, String body) {}

    default void responseTrailers(RequestContext ctx, Map<String, String> trailers) {}

    default void errorHandler(RequestContext ctx, RequestCase phase, Throwable error) {}
  }

  private final String name;
  private final Map<String, CaseHandler> cases = new ConcurrentHashMap<String, CaseHandler>();
  private final ProcessingOptions options;

  public ConformanceProcessor(String name) {
    this.name = name;
    this.options = new ProcessingOptions();
  }

  public ConformanceProcessor(String name, ProcessingOptions options) {
    this.name = name;
    this.options = options;
  }

  public static ConformanceProcessor newDefault(String name) {
    return new ConformanceProcessor(name, new ProcessingOptions());
  }

  public String getName() {
    return name;
  }

  public ProcessingOptions getOptions() {
    return options;
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {}

  public void close(int gracePeriodSeconds) {
    shutdown();
  }

  public void errorHandler(RequestContext ctx, RequestCase phase, Throwable error) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, null));
    if (handler == null) {
      return;
    }
    try {
      handler.errorHandler(ctx, phase, error);
    } catch (Exception e) {
      // swallow
    }
  }

  private String resolveCaseId(RequestContext ctx, Map<String, String> headers) {
    String fromHeaders = headers != null ? headers.get(CASE_HEADER) : null;
    if (fromHeaders != null) {
      return fromHeaders;
    }
    Object value = ctx.getValue(CASE_HEADER);
    if (value != null) {
      return value.toString();
    }
    String header = ctx.getRequestHeader(CASE_HEADER);
    if (header != null) {
      return header;
    }
    return "";
  }

  public void register(String caseId, CaseHandler handler) {
    cases.put(caseId, handler);
  }

  public void unregister(String caseId) {
    cases.remove(caseId);
  }

  public boolean hasCase(String caseId) {
    return cases.containsKey(caseId);
  }

  @Override
  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    String resolvedCaseId = resolveCaseId(ctx, headers);
    CaseHandler handler = cases.get(resolvedCaseId);
    if (handler == null) {
      return;
    }
    handler.requestHeaders(ctx, headers);
  }

  @Override
  public void processRequestBody(RequestContext ctx, String body) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, null));
    if (handler == null) {
      return;
    }
    handler.requestBody(ctx, body);
  }

  @Override
  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, null));
    if (handler == null) {
      return;
    }
    handler.requestTrailers(ctx, trailers);
  }

  @Override
  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, headers));
    if (handler == null) {
      return;
    }
    handler.responseHeaders(ctx, headers);
  }

  @Override
  public void processResponseBody(RequestContext ctx, String body) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, null));
    if (handler == null) {
      return;
    }
    handler.responseBody(ctx, body);
  }

  @Override
  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    CaseHandler handler = cases.get(resolveCaseId(ctx, null));
    if (handler == null) {
      return;
    }
    handler.responseTrailers(ctx, trailers);
  }
}
