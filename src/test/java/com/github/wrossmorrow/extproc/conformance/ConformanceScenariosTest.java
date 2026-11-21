package com.github.wrossmorrow.extproc.conformance;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Arrays;
import java.util.Collections;
import java.util.List;
import org.junit.jupiter.api.Test;

public class ConformanceScenariosTest {

  @Test
  void scenarioRegistryContainsPhaseTwoScenarios() {
    List<String> names = ConformanceScenarios.scenarioNames();
    assertTrue(names.contains("headers_mutations_http1"), names.toString());
    assertTrue(names.contains("headers_mutations_h2c"), names.toString());
    assertTrue(names.contains("headers_multivalue_and_context_state"), names.toString());
    assertTrue(names.contains("skip_request_headers_response_mutation"), names.toString());
    assertTrue(names.contains("multi_filter_chaining"), names.toString());
    assertTrue(names.contains("header_append_action_envoy_gap"), names.toString());
    assertTrue(names.contains("get_nobody_mode_none"), names.toString());
    assertTrue(names.contains("post_with_body_mode_none"), names.toString());
    assertTrue(names.contains("buffered_body_replace_grow_and_shrink"), names.toString());
    assertTrue(names.contains("buffered_body_clear"), names.toString());
    assertTrue(
        names.contains("http1_chunked_response_buffered_replace_and_clear"), names.toString());
    assertTrue(names.contains("streamed_body_chunks"), names.toString());
    assertTrue(names.contains("streamed_request_and_response_clear_chunk"), names.toString());
    assertTrue(names.contains("buffered_partial_mode"), names.toString());
    assertTrue(names.contains("http1_trailers_roundtrip"), names.toString());
    assertTrue(names.contains("h2c_trailers_roundtrip"), names.toString());
    assertTrue(names.contains("clear_route_cache_upstream_switch"), names.toString());
    assertTrue(names.contains("clear_route_cache_from_request_body"), names.toString());
    assertTrue(names.contains("immediate_response_request_headers"), names.toString());
    assertTrue(
        names.contains("immediate_response_all_phases_and_trailers_fallback"), names.toString());
    assertTrue(names.contains("per_route_disabled_bypass"), names.toString());
    assertTrue(names.contains("downstream_client_cancel_graceful"), names.toString());
    assertTrue(names.contains("message_timeout_fail_closed_and_fail_open"), names.toString());
    assertTrue(names.contains("processor_error_continue_vs_abort"), names.toString());
    assertTrue(names.contains("observability_mode_envoy_gap"), names.toString());
  }

  @Test
  void runUnknownScenarioYieldsSkip() {
    ConformanceScenarios.ScenarioContext ctx =
        new ConformanceScenarios.ScenarioContext(
            null, null, null, new EnvoyHttpClient("http://127.0.0.1:1", 1000));
    ConformanceScenarios.ScenarioResult result = ConformanceScenarios.run("no_such_scenario", ctx);
    assertEquals(ConformanceScenarios.Outcome.SKIP, result.outcome);
  }

  @Test
  void runKnownScenarioYieldsOutcome() {
    ConformanceScenarios.ScenarioContext ctx =
        new ConformanceScenarios.ScenarioContext(
            null,
            ConformanceProcessor.newDefault("conformance"),
            null,
            new EnvoyHttpClient("http://127.0.0.1:1", 1000));
    ConformanceScenarios.ScenarioResult result =
        ConformanceScenarios.run("buffered_body_clear", ctx);
    assertEquals(ConformanceScenarios.Outcome.PASS, result.outcome);
  }

  @Test
  void runImplementedScenariosReturnPass() {
    ConformanceScenarios.ScenarioContext ctx =
        new ConformanceScenarios.ScenarioContext(
            null,
            ConformanceProcessor.newDefault("conformance"),
            null,
            new EnvoyHttpClient("http://127.0.0.1:1", 1000));
    List<String> implemented =
        java.util.Arrays.asList(
            "headers_mutations_http1",
            "post_with_body_mode_none",
            "buffered_body_replace_grow_and_shrink",
            "buffered_body_clear",
            "clear_route_cache_from_request_body",
            "immediate_response_request_headers",
            "skip_request_headers_response_mutation",
            "streamed_body_chunks",
            "http1_chunked_response_buffered_replace_and_clear",
            "streamed_request_and_response_clear_chunk");
    List<ConformanceScenarios.ScenarioResult> results =
        ConformanceScenarios.runAll(ctx, implemented);
    assertEquals(implemented.size(), results.size());
    for (ConformanceScenarios.ScenarioResult result : results) {
      assertEquals(ConformanceScenarios.Outcome.PASS, result.outcome);
    }
  }

  @Test
  void runAllReturnsResultsForRequestedNames() {
    ConformanceScenarios.ScenarioContext ctx =
        new ConformanceScenarios.ScenarioContext(
            null,
            ConformanceProcessor.newDefault("conformance"),
            null,
            new EnvoyHttpClient("http://127.0.0.1:1", 1000));
    List<ConformanceScenarios.ScenarioResult> results =
        ConformanceScenarios.runAll(ctx, Collections.singletonList("post_with_body_mode_none"));
    assertEquals(1, results.size());
    assertEquals("post_with_body_mode_none", results.get(0).scenario);
    assertEquals(ConformanceScenarios.Outcome.PASS, results.get(0).outcome);
  }

  @Test
  void scenarioNamesContainPhaseTwoSet() {
    List<String> names = ConformanceScenarios.scenarioNames();
    assertEquals(25, names.size());
    for (String expected :
        Arrays.asList(
            "headers_mutations_http1",
            "headers_mutations_h2c",
            "headers_multivalue_and_context_state",
            "skip_request_headers_response_mutation",
            "multi_filter_chaining",
            "header_append_action_envoy_gap",
            "get_nobody_mode_none",
            "post_with_body_mode_none",
            "buffered_body_replace_grow_and_shrink",
            "buffered_body_clear",
            "http1_chunked_response_buffered_replace_and_clear",
            "streamed_body_chunks",
            "streamed_request_and_response_clear_chunk",
            "buffered_partial_mode",
            "http1_trailers_roundtrip",
            "h2c_trailers_roundtrip",
            "clear_route_cache_upstream_switch",
            "clear_route_cache_from_request_body",
            "immediate_response_request_headers",
            "immediate_response_all_phases_and_trailers_fallback",
            "per_route_disabled_bypass",
            "downstream_client_cancel_graceful",
            "message_timeout_fail_closed_and_fail_open",
            "processor_error_continue_vs_abort",
            "observability_mode_envoy_gap")) {
      assertTrue(names.contains(expected), expected);
    }
  }
}
