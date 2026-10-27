package com.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import com.github.wrossmorrow.extproc.Metrics;
import io.prometheus.client.Collector;
import io.prometheus.client.Collector.Type;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.exporter.HTTPServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class MetricsTest {

  @Test
  void collectorsCoverAllMetrics() {
    Metrics metrics = new Metrics();
    List<Collector> collectors = metrics.collectors();
    assertEquals(16, collectors.size());
  }

  @Test
  void registerAndGatherExposesMetrics() {
    Metrics metrics = new Metrics();
    CollectorRegistry registry = metrics.registry();

    List<Collector.MetricFamilySamples> families = new ArrayList<Collector.MetricFamilySamples>();
    java.util.Enumeration<Collector.MetricFamilySamples> enumeration =
        registry.metricFamilySamples();
    while (enumeration.hasMoreElements()) {
      families.add(enumeration.nextElement());
    }

    boolean foundTotalStreams = false;
    boolean foundActiveStreams = false;
    for (Collector.MetricFamilySamples family : families) {
      if (family.name.equals(Metrics.TOTAL_STREAMS)) {
        foundTotalStreams = true;
        assertEquals(Type.COUNTER, family.type);
      }
      if (family.name.equals(Metrics.ACTIVE_STREAMS)) {
        foundActiveStreams = true;
        assertEquals(Type.GAUGE, family.type);
      }
    }
    assertTrue(foundTotalStreams);
    assertTrue(foundActiveStreams);
  }

  @Test
  void httpServerServesMetricsEndpoint() throws Exception {
    Metrics metrics = new Metrics();
    HTTPServer server = new HTTPServer(new InetSocketAddress(19090), metrics.registry(), false);
    try {
      HttpClient client = HttpClient.newHttpClient();
      HttpRequest request =
          HttpRequest.newBuilder(URI.create("http://localhost:19090/metrics")).build();
      HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
      assertEquals(200, response.statusCode());
      assertTrue(response.body().contains(Metrics.TOTAL_STREAMS));
    } finally {
      server.stop();
    }
  }
}
