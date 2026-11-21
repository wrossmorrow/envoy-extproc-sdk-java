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
    assertTrue(names.contains("post_with_body_mode_none"), names.toString());
    assertTrue(names.contains("buffered_body_replace_grow_and_shrink"), names.toString());
    assertTrue(names.contains("buffered_body_clear"), names.toString());
    assertTrue(names.contains("clear_route_cache_from_request_body"), names.toString());
    assertTrue(names.contains("immediate_response_request_headers"), names.toString());
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
    assertEquals(6, names.size());
    for (String expected :
        Arrays.asList(
            "headers_mutations_http1",
            "post_with_body_mode_none",
            "buffered_body_replace_grow_and_shrink",
            "buffered_body_clear",
            "clear_route_cache_from_request_body",
            "immediate_response_request_headers")) {
      assertTrue(names.contains(expected), expected);
    }
  }
}
