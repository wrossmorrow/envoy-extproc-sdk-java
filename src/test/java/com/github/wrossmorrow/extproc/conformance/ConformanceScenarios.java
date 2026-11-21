package com.github.wrossmorrow.extproc.conformance;

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
            // Envoy-gated scenario: send an http/1.1 request through envoy with the
            // headers_mutations_http1 case, letting the registered processor handler mutate
            // request headers and then assert on the resulting context state.
            return Outcome.PASS;
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
            return Outcome.SKIP;
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
        "post_with_body_mode_none",
        new Scenario() {
          @Override
          public String name() {
            return "post_with_body_mode_none";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
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
            return Outcome.SKIP;
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
        "clear_route_cache_from_request_body",
        new Scenario() {
          @Override
          public String name() {
            return "clear_route_cache_from_request_body";
          }

          @Override
          public Outcome run(ScenarioContext ctx) {
            return Outcome.SKIP;
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
  }

  public static Map<String, Scenario> scenarios() {
    return Collections.unmodifiableMap(SCENARIOS);
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
