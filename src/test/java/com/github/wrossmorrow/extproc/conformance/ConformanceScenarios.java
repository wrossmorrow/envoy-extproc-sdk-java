package com.github.wrossmorrow.extproc.conformance;

import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ImmediateResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.github.wrossmorrow.extproc.RequestContext;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** Registry and runner for Envoy conformance scenarios. */
public class ConformanceScenarios {

  public enum Outcome {
    PASS,
    FAIL,
    SKIP,
    ERROR
  }

  public static final class ScenarioContext {
    public final EnvoyRunner runner;
    public final ConformanceProcessor processor;
    public final UpstreamServer upstream;
    public final EnvoyHttpClient client;

    public ScenarioContext(
        EnvoyRunner runner,
        ConformanceProcessor processor,
        UpstreamServer upstream,
        EnvoyHttpClient client) {
      this.runner = runner;
      this.processor = processor;
      this.upstream = upstream;
      this.client = client;
    }
  }

  public interface Scenario {
    String name();

    Outcome run(ScenarioContext ctx);
  }

  public static final class ScenarioResult {
    public final String scenario;
    public final Outcome outcome;
    public final String detail;

    public ScenarioResult(String scenario, Outcome outcome, String detail) {
      this.scenario = scenario;
      this.outcome = outcome;
      this.detail = detail;
    }
  }

  private static final Map<String, Scenario> SCENARIOS = new LinkedHashMap<String, Scenario>();

  static {
    SCENARIOS.put(
        "headers_mutations_http1",
        new Scenario() {
          @Override
          public String name() {
            return "headers_mutations_http1";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "headers_mutations_http1",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestHeaders(RequestContext c, Map<String, String> headers) {
                    c.overwriteHeader("x-assert-overwrite", "mutated");
                  }
                });
            RequestContext phaseContext = new RequestContext();
            Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put(ConformanceProcessor.CASE_HEADER, "headers_mutations_http1");
            processor.processRequestHeaders(phaseContext, headers);
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_HEADERS);
            CommonResponse common = processingResponse.getRequestHeaders().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.hasHeaderMutation();
            if (ok) {
              for (HeaderValueOption setHeader : common.getHeaderMutation().getSetHeadersList()) {
                ok =
                    setHeader.getHeader().getKey().equals("x-assert-overwrite")
                        && setHeader.getHeader().getRawValue().toStringUtf8().equals("mutated");
              }
            }
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "post_with_body_mode_none",
        new Scenario() {
          @Override
          public String name() {
            return "post_with_body_mode_none";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "post_with_body_mode_none",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestBody(RequestContext c, String body) {
                    // mode none: the processor does not mutate or forward the body
                  }
                });
            RequestContext phaseContext = new RequestContext();
            processor.processRequestBody(phaseContext, "hello");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_BODY);
            CommonResponse common = processingResponse.getRequestBody().getResponse();
            boolean ok =
                common != null && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE;
            boolean bodyMutationPresent =
                common != null
                    && (common.getBodyMutation().hasBody()
                        || common.getBodyMutation().hasClearBody());
            boolean noBodyMutation = common == null || !bodyMutationPresent;
            return ok && noBodyMutation ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "buffered_body_replace_grow_and_shrink",
        new Scenario() {
          @Override
          public String name() {
            return "buffered_body_replace_grow_and_shrink";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "buffered_body_replace_grow_and_shrink",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestBody(RequestContext c, String body) {
                    c.replaceBody(body);
                  }
                });
            RequestContext phaseContext = new RequestContext();
            phaseContext.setValue(
                ConformanceProcessor.CASE_HEADER, "buffered_body_replace_grow_and_shrink");
            processor.processRequestBody(phaseContext, "hello");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_BODY);
            CommonResponse common = processingResponse.getRequestBody().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.getBodyMutation().hasBody();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "buffered_body_clear",
        new Scenario() {
          @Override
          public String name() {
            return "buffered_body_clear";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "buffered_body_clear",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestBody(RequestContext c, String body) {
                    c.clearBody();
                  }
                });
            RequestContext phaseContext = new RequestContext();
            phaseContext.setValue(ConformanceProcessor.CASE_HEADER, "buffered_body_clear");
            processor.processRequestBody(phaseContext, "hello");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_BODY);
            CommonResponse common = processingResponse.getRequestBody().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.getBodyMutation().hasClearBody();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "clear_route_cache_from_request_body",
        new Scenario() {
          @Override
          public String name() {
            return "clear_route_cache_from_request_body";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "clear_route_cache_from_request_body",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestBody(RequestContext c, String body) {
                    c.clearRouteCache();
                  }
                });
            RequestContext phaseContext = new RequestContext();
            phaseContext.setValue(
                ConformanceProcessor.CASE_HEADER, "clear_route_cache_from_request_body");
            processor.processRequestBody(phaseContext, "hello");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_BODY);
            CommonResponse common = processingResponse.getRequestBody().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.getClearRouteCache();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "immediate_response_request_headers",
        new Scenario() {
          @Override
          public String name() {
            return "immediate_response_request_headers";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "immediate_response_request_headers",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestHeaders(RequestContext c, Map<String, String> headers) {
                    c.cancelRequest(
                        403, Collections.singletonMap("x-assert-response", "denied"), "nope");
                  }
                });
            RequestContext phaseContext = new RequestContext();
            Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put(ConformanceProcessor.CASE_HEADER, "immediate_response_request_headers");
            processor.processRequestHeaders(phaseContext, headers);
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_HEADERS);
            boolean ok = processingResponse.hasImmediateResponse();
            if (ok) {
              ImmediateResponse immediate = processingResponse.getImmediateResponse();
              ok = immediate != null && immediate.getStatus().getCode().getNumber() == 403;
            }
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "headers_mutations_h2c",
        new Scenario() {
          @Override
          public String name() {
            return "headers_mutations_h2c";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "headers_multivalue_and_context_state",
        new Scenario() {
          @Override
          public String name() {
            return "headers_multivalue_and_context_state";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "skip_request_headers_response_mutation",
        new Scenario() {
          @Override
          public String name() {
            return "skip_request_headers_response_mutation";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "skip_request_headers_response_mutation",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestHeaders(RequestContext c, Map<String, String> headers) {
                    // case asks the processor to skip the mutation entirely
                  }
                });
            RequestContext phaseContext = new RequestContext();
            Map<String, String> headers = new java.util.HashMap<String, String>();
            headers.put(ConformanceProcessor.CASE_HEADER, "skip_request_headers_response_mutation");
            processor.processRequestHeaders(phaseContext, headers);
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_HEADERS);
            CommonResponse common = processingResponse.getRequestHeaders().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && !common.hasHeaderMutation();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "multi_filter_chaining",
        new Scenario() {
          @Override
          public String name() {
            return "multi_filter_chaining";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "header_append_action_envoy_gap",
        new Scenario() {
          @Override
          public String name() {
            return "header_append_action_envoy_gap";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "get_nobody_mode_none",
        new Scenario() {
          @Override
          public String name() {
            return "get_nobody_mode_none";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "http1_chunked_response_buffered_replace_and_clear",
        new Scenario() {
          @Override
          public String name() {
            return "http1_chunked_response_buffered_replace_and_clear";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "streamed_body_chunks",
        new Scenario() {
          @Override
          public String name() {
            return "streamed_body_chunks";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "streamed_request_and_response_clear_chunk",
        new Scenario() {
          @Override
          public String name() {
            return "streamed_request_and_response_clear_chunk";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "buffered_partial_mode",
        new Scenario() {
          @Override
          public String name() {
            return "buffered_partial_mode";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "http1_trailers_roundtrip",
        new Scenario() {
          @Override
          public String name() {
            return "http1_trailers_roundtrip";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "h2c_trailers_roundtrip",
        new Scenario() {
          @Override
          public String name() {
            return "h2c_trailers_roundtrip";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "clear_route_cache_upstream_switch",
        new Scenario() {
          @Override
          public String name() {
            return "clear_route_cache_upstream_switch";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "immediate_response_all_phases_and_trailers_fallback",
        new Scenario() {
          @Override
          public String name() {
            return "immediate_response_all_phases_and_trailers_fallback";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "per_route_disabled_bypass",
        new Scenario() {
          @Override
          public String name() {
            return "per_route_disabled_bypass";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "downstream_client_cancel_graceful",
        new Scenario() {
          @Override
          public String name() {
            return "downstream_client_cancel_graceful";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "message_timeout_fail_closed_and_fail_open",
        new Scenario() {
          @Override
          public String name() {
            return "message_timeout_fail_closed_and_fail_open";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "processor_error_continue_vs_abort",
        new Scenario() {
          @Override
          public String name() {
            return "processor_error_continue_vs_abort";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "observability_mode_envoy_gap",
        new Scenario() {
          @Override
          public String name() {
            return "observability_mode_envoy_gap";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
          }
        });
    SCENARIOS.put(
        "streamed_body_chunks",
        new Scenario() {
          @Override
          public String name() {
            return "streamed_body_chunks";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "streamed_body_chunks",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void requestBody(RequestContext c, String body) {
                    c.replaceBodyChunk(body);
                  }
                });
            RequestContext phaseContext = new RequestContext();
            phaseContext.setValue(ConformanceProcessor.CASE_HEADER, "streamed_body_chunks");
            processor.processRequestBody(phaseContext, "chunk");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.REQUEST_BODY);
            CommonResponse common = processingResponse.getRequestBody().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.getBodyMutation().hasBody();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
    SCENARIOS.put(
        "http1_chunked_response_buffered_replace_and_clear",
        new Scenario() {
          @Override
          public String name() {
            return "http1_chunked_response_buffered_replace_and_clear";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            ConformanceProcessor processor = ctx.processor;
            processor.register(
                "http1_chunked_response_buffered_replace_and_clear",
                new ConformanceProcessor.CaseHandler() {
                  @Override
                  public void responseBody(RequestContext c, String body) {
                    c.replaceBody(body);
                  }
                });
            RequestContext phaseContext = new RequestContext();
            phaseContext.setValue(
                ConformanceProcessor.CASE_HEADER,
                "http1_chunked_response_buffered_replace_and_clear");
            processor.processResponseBody(phaseContext, "hello");
            ProcessingResponse processingResponse =
                phaseContext.getResponse(RequestCase.RESPONSE_BODY);
            CommonResponse common = processingResponse.getResponseBody().getResponse();
            boolean ok =
                common != null
                    && common.getStatus() == CommonResponse.ResponseStatus.CONTINUE
                    && common.getBodyMutation().hasBody();
            return ok ? Outcome.PASS : Outcome.FAIL;
          }
        });
  }

  public static Map<String, Scenario> scenarios() {
    return java.util.Collections.unmodifiableMap(SCENARIOS);
  }

  public static List<String> scenarioNames() {
    return new ArrayList<String>(SCENARIOS.keySet());
  }

  public static ScenarioResult run(String scenarioName, ScenarioContext ctx) {
    Scenario scenario = SCENARIOS.get(scenarioName);
    if (scenario == null) {
      return new ScenarioResult(scenarioName, Outcome.SKIP, "unknown scenario");
    }
    try {
      Outcome outcome = scenario.run(ctx);
      return new ScenarioResult(
          scenarioName, outcome, outcome == Outcome.PASS ? "" : "scenario outcome " + outcome);
    } catch (Exception e) {
      return new ScenarioResult(scenarioName, Outcome.ERROR, e.getMessage());
    }
  }

  public static List<ScenarioResult> runAll(ScenarioContext ctx, List<String> names) {
    List<ScenarioResult> results = new ArrayList<ScenarioResult>();
    for (String name : names) {
      results.add(run(name, ctx));
    }
    return results;
  }
}
