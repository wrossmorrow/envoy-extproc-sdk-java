package com.github.wrossmorrow.extproc;

import io.prometheus.client.Collector;
import io.prometheus.client.CollectorRegistry;
import io.prometheus.client.Counter;
import io.prometheus.client.Gauge;
import io.prometheus.client.Histogram;
import io.prometheus.client.exporter.HTTPServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.ArrayList;
import java.util.List;

/** Prometheus metrics for the ext_proc server, mirroring the Go and Rust SDKs. */
public class Metrics {

  public static final String ACTIVE_STREAMS = "extproc_active_streams";
  public static final String ERRORED_STREAMS = "extproc_errored_streams";
  public static final String PHASE_ERRORS = "extproc_phase_errors_total";
  public static final String TOTAL_STREAMS = "extproc_total_streams";
  public static final String STREAM_DURATION_SECONDS = "extproc_stream_duration_seconds";
  public static final String EXTERNAL_PROCESSING_SECONDS = "extproc_external_processing_seconds";
  public static final String TOTAL_REQUEST_HEADERS = "extproc_total_request_headers";
  public static final String TOTAL_REQUEST_BODY = "extproc_request_body_messages_total";
  public static final String TOTAL_REQUEST_TRAILERS = "extproc_total_request_trailers";
  public static final String TOTAL_RESPONSE_HEADERS = "extproc_total_response_headers";
  public static final String TOTAL_RESPONSE_BODY = "extproc_response_body_messages_total";
  public static final String TOTAL_RESPONSE_TRAILERS = "extproc_total_response_trailers";
  public static final String TOTAL_EMPTY_RESPONSES = "extproc_empty_responses";
  public static final String REQUEST_BODY_BYTES = "extproc_request_body_bytes_total";
  public static final String RESPONSE_BODY_BYTES = "extproc_response_body_bytes_total";
  public static final String RESPONSE_SEND_ERRORS = "extproc_response_send_errors";

  private final CollectorRegistry registry = new CollectorRegistry();

  public final Gauge activeStreams;
  public final Counter erroredStreams;
  public final Counter phaseErrors;
  public final Counter totalStreams;
  public final Histogram streamDurationSeconds;
  public final Histogram externalProcessingSeconds;
  public final Counter totalRequestHeaders;
  public final Counter totalRequestBody;
  public final Counter totalRequestTrailers;
  public final Counter totalResponseHeaders;
  public final Counter totalResponseBody;
  public final Counter totalResponseTrailers;
  public final Counter totalEmptyResponses;
  public final Counter requestBodyBytes;
  public final Counter responseBodyBytes;
  public final Counter responseSendErrors;

  public Metrics() {
    this.activeStreams = Gauge.build(ACTIVE_STREAMS, "Current number of active streams.").create();
    this.erroredStreams =
        Counter.build(
                ERRORED_STREAMS, "Total number of streams that errored, counted once per stream.")
            .create();
    this.phaseErrors =
        Counter.build(
                PHASE_ERRORS,
                "Total number of phase processing errors, which may repeat within one stream.")
            .create();
    this.totalStreams = Counter.build(TOTAL_STREAMS, "Total number of streams processed.").create();
    this.streamDurationSeconds =
        Histogram.build(STREAM_DURATION_SECONDS, "Stream duration in seconds (native histogram).")
            .create();
    this.externalProcessingSeconds =
        Histogram.build(
                EXTERNAL_PROCESSING_SECONDS,
                "External processing time in seconds (native histogram).")
            .create();
    this.totalRequestHeaders =
        Counter.build(TOTAL_REQUEST_HEADERS, "Total number of request headers processed.").create();
    this.totalRequestBody =
        Counter.build(TOTAL_REQUEST_BODY, "Total number of request bodies processed.").create();
    this.totalRequestTrailers =
        Counter.build(TOTAL_REQUEST_TRAILERS, "Total number of request trailers processed.")
            .create();
    this.totalResponseHeaders =
        Counter.build(TOTAL_RESPONSE_HEADERS, "Total number of response headers processed.")
            .create();
    this.totalResponseBody =
        Counter.build(TOTAL_RESPONSE_BODY, "Total number of response bodies processed.").create();
    this.totalResponseTrailers =
        Counter.build(TOTAL_RESPONSE_TRAILERS, "Total number of response trailers processed.")
            .create();
    this.totalEmptyResponses =
        Counter.build(TOTAL_EMPTY_RESPONSES, "Total number of empty responses processed.").create();
    this.requestBodyBytes =
        Counter.build(REQUEST_BODY_BYTES, "Total number of body bytes received.").create();
    this.responseBodyBytes =
        Counter.build(RESPONSE_BODY_BYTES, "Total number of body bytes returned.").create();
    this.responseSendErrors =
        Counter.build(RESPONSE_SEND_ERRORS, "Total number of response send errors.").create();
    register();
  }

  public void register() {
    registry.register(activeStreams);
    registry.register(erroredStreams);
    registry.register(phaseErrors);
    registry.register(totalStreams);
    registry.register(streamDurationSeconds);
    registry.register(externalProcessingSeconds);
    registry.register(totalRequestHeaders);
    registry.register(totalRequestBody);
    registry.register(totalRequestTrailers);
    registry.register(totalResponseHeaders);
    registry.register(totalResponseBody);
    registry.register(totalResponseTrailers);
    registry.register(totalEmptyResponses);
    registry.register(requestBodyBytes);
    registry.register(responseBodyBytes);
    registry.register(responseSendErrors);
  }

  public CollectorRegistry registry() {
    return registry;
  }

  public List<Collector> collectors() {
    List<Collector> collectors = new ArrayList<Collector>();
    collectors.add(activeStreams);
    collectors.add(erroredStreams);
    collectors.add(phaseErrors);
    collectors.add(totalStreams);
    collectors.add(streamDurationSeconds);
    collectors.add(externalProcessingSeconds);
    collectors.add(totalRequestHeaders);
    collectors.add(totalRequestBody);
    collectors.add(totalRequestTrailers);
    collectors.add(totalResponseHeaders);
    collectors.add(totalResponseBody);
    collectors.add(totalResponseTrailers);
    collectors.add(totalEmptyResponses);
    collectors.add(requestBodyBytes);
    collectors.add(responseBodyBytes);
    collectors.add(responseSendErrors);
    return collectors;
  }

  /** Start a Prometheus metrics HTTP server on the given port, serving this registry. */
  public HTTPServer newHTTPServer(int port) throws IOException {
    return new HTTPServer(new InetSocketAddress(port), registry, false);
  }
}
