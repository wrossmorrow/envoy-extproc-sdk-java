package com.github.wrossmorrow.extproc.loadgen;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.logging.Logger;

/**
 * Plain gRPC load generator for the envoy ext_proc SDK (Java).
 *
 * <p>Drives full ext_proc exchanges over a raw bidirectional gRPC stream to the SDK's ext_proc
 * server (`envoy.service.ext_proc.v3.ExternalProcessor/Process`), without an envoy in the path, and
 * reports per-exchange latency and errors.
 *
 * <p>Each exchange mirrors the phase sequence the envoy ext_proc filter drives for a buffered
 * request/response: request headers -> request body -> response headers -> response body, then the
 * stream is closed. Because envoy (and the HTTP machinery in between) are absent, the reported
 * latency is the processor-side cost of the SDK's gRPC server path, complementing the containerized
 * k6 load tests in `loadtest` that drive the full envoy -> extproc -> upstream chain.
 */
public class LoadGen {

  private static final Logger logger = Logger.getLogger(LoadGen.class.getName());

  /**
   * The response header the server may set (via the ProcessingOptions.UpdateDurationHeader option)
   * carrying the accumulated extproc processing time in nanoseconds.
   */
  static final String DURATION_HEADER = "x-extproc-duration-ns";

  static final String DEFAULT_ADDR = "127.0.0.1:50051";
  static final int DEFAULT_ITERATIONS = 100;
  static final int DEFAULT_CONCURRENCY = 10;

  public static void main(String[] args) throws Exception {
    String addr = DEFAULT_ADDR;
    int iterations = DEFAULT_ITERATIONS;
    int concurrency = DEFAULT_CONCURRENCY;
    for (int i = 0; i < args.length; i++) {
      String arg = args[i];
      if (arg.startsWith("--addr=")) {
        addr = arg.substring("--addr=".length());
      } else if (arg.startsWith("--iterations=")) {
        iterations = Integer.parseInt(arg.substring("--iterations=".length()));
      } else if (arg.startsWith("--concurrency=")) {
        concurrency = Integer.parseInt(arg.substring("--concurrency=".length()));
      }
    }

    if (iterations <= 0) {
      System.err.println("LoadGen: iterations must be greater than zero");
      System.exit(1);
    }

    System.out.println("LoadGen: target extproc gRPC server: http://" + addr);
    System.out.println(
        "LoadGen: running " + iterations + " exchanges with concurrency " + concurrency);

    int port = 50051;
    String host = addr;
    int colon = addr.lastIndexOf(':');
    if (colon >= 0) {
      host = addr.substring(0, colon);
      port = Integer.parseInt(addr.substring(colon + 1));
    }
    ManagedChannel channel =
        ManagedChannelBuilder.forAddress(host, port).useTransportSecurity().build();
    ExternalProcessorGrpc.ExternalProcessorStub stub = ExternalProcessorGrpc.newStub(channel);
    ExecutorService executor = Executors.newFixedThreadPool(concurrency);
    try {
      List<WorkerResult> workerResults = new ArrayList<>();
      List<Future<WorkerResult>> futures = new ArrayList<>();
      for (int i = 0; i < concurrency; i++) {
        final int base = i;
        final ExternalProcessorGrpc.ExternalProcessorStub workerStub = stub;
        final int workerIterations = iterations;
        final int workerConcurrency = concurrency;
        futures.add(
            executor.submit(
                () -> runWorker(workerStub, base, workerConcurrency, workerIterations)));
      }
      for (Future<WorkerResult> future : futures) {
        workerResults.add(future.get(30, TimeUnit.SECONDS));
      }

      List<Duration> latencies = new ArrayList<>();
      List<Long> extprocDurationsNs = new ArrayList<>();
      List<ExchangeError> errors = new ArrayList<>();
      for (WorkerResult result : workerResults) {
        latencies.addAll(result.latencies);
        extprocDurationsNs.addAll(result.extprocDurationsNs);
        errors.addAll(result.errors);
      }
      latencies.sort((left, right) -> left.compareTo(right));
      double avgMs = 0.0;
      if (!latencies.isEmpty()) {
        Duration sum = Duration.ZERO;
        for (Duration d : latencies) {
          sum = sum.plus(d);
        }
        avgMs = sum.toMillis() / (double) latencies.size();
      }
      Double p50Ms = percentile(latencies, 0.50);
      Double p95Ms = percentile(latencies, 0.95);
      double maxMs = latencies.isEmpty() ? 0.0 : latencies.get(latencies.size() - 1).toMillis();
      double avgDurationMs = 0.0;
      if (!extprocDurationsNs.isEmpty()) {
        long sumNs = 0;
        for (long ns : extprocDurationsNs) {
          sumNs += ns;
        }
        avgDurationMs = sumNs / ((double) extprocDurationsNs.size() * 1e6);
      }

      for (ExchangeError e : errors) {
        System.out.println("LoadGen: exchange " + e.index + " failed: " + e.error);
      }
      System.out.println(
          "LoadGen: finished: " + latencies.size() + " exchanges, " + errors.size() + " errors");
      System.out.println(
          "LoadGen: latency avg="
              + avgMs
              + "ms p50="
              + p50Ms
              + "ms p95="
              + p95Ms
              + "ms max="
              + maxMs
              + "ms");
      System.out.println(
          "LoadGen: extproc duration avg="
              + avgDurationMs
              + "ms ("
              + extprocDurationsNs.size()
              + " samples)");

      if (!errors.isEmpty()) {
        System.exit(1);
      }
    } finally {
      executor.shutdownNow();
    }

    channel.shutdownNow();
  }

  static WorkerResult runWorker(
      ExternalProcessorGrpc.ExternalProcessorStub stub, int base, int stride, int iterations) {
    WorkerResult result = new WorkerResult(new ArrayList<>(), new ArrayList<>(), new ArrayList<>());
    int index = base;
    while (index < iterations) {
      ExchangeOutcome outcome = runExchange(stub);
      if (outcome.error != null) {
        result.errors.add(new ExchangeError(index, outcome.error));
      } else {
        result.latencies.add(outcome.latency);
        if (outcome.extprocDurationNs != null) {
          result.extprocDurationsNs.add(outcome.extprocDurationNs);
        }
      }
      index += stride;
    }
    return result;
  }

  static ExchangeOutcome runExchange(ExternalProcessorGrpc.ExternalProcessorStub stub) {
    Instant start = Instant.now();
    ResponseCollector responseObserver = new ResponseCollector();
    StreamObserver<ProcessingRequest> requestObserver = stub.process(responseObserver);

    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(requestHeaderMap("POST", "/test"))
                    .setEndOfStream(false)
                    .build())
            .build());
    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("{}"))
                    .setEndOfStream(true)
                    .build())
            .build());
    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setResponseHeaders(HttpHeaders.newBuilder().setEndOfStream(true).build())
            .build());
    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setResponseBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8("{}"))
                    .setEndOfStream(true)
                    .build())
            .build());
    requestObserver.onCompleted();

    Long extprocDurationNs = extractDurationNs(responseObserver.lastResponse);
    Duration latency = Duration.between(start, Instant.now());
    String error = responseObserver.error == null ? null : responseObserver.error.getMessage();
    return new ExchangeOutcome(latency, extprocDurationNs, error);
  }

  static Long extractDurationNs(ProcessingResponse response) {
    if (response == null) {
      return null;
    }
    CommonResponse common;
    if (response.getResponseCase() == ProcessingResponse.ResponseCase.RESPONSE_HEADERS) {
      common = response.getResponseHeaders().getResponse();
    } else if (response.getResponseCase() == ProcessingResponse.ResponseCase.RESPONSE_BODY) {
      common = response.getResponseBody().getResponse();
    } else {
      return null;
    }
    if (common == null || !common.hasHeaderMutation()) {
      return null;
    }
    for (HeaderValueOption option : common.getHeaderMutation().getSetHeadersList()) {
      HeaderValue header = option.getHeader();
      if (header == null || !DURATION_HEADER.equals(header.getKey())) {
        continue;
      }
      ByteString raw = header.getRawValue();
      if (raw == null || raw.size() == 0) {
        return null;
      }
      try {
        return Long.valueOf(
            new String(raw.toByteArray(), java.nio.charset.StandardCharsets.UTF_8).trim());
      } catch (Exception e) {
        return null;
      }
    }
    return null;
  }

  static HeaderMap requestHeaderMap(String method, String path) {
    HeaderMap.Builder builder = HeaderMap.newBuilder();
    builder.addHeaders(
        HeaderValue.newBuilder()
            .setKey(":method")
            .setRawValue(ByteString.copyFromUtf8(method))
            .build());
    builder.addHeaders(
        HeaderValue.newBuilder()
            .setKey(":path")
            .setRawValue(ByteString.copyFromUtf8(path))
            .build());
    return builder.build();
  }

  static double percentile(List<Duration> sorted, double percentile) {
    if (sorted.isEmpty()) {
      return 0.0;
    }
    int rank = (int) Math.round((double) (sorted.size() - 1) * percentile);
    return sorted.get(rank).toMillis();
  }

  static final class ResponseCollector implements StreamObserver<ProcessingResponse> {
    ProcessingResponse lastResponse;
    Throwable error;
    boolean completed;

    @Override
    public void onNext(ProcessingResponse value) {
      lastResponse = value;
    }

    @Override
    public void onError(Throwable t) {
      error = t;
    }

    @Override
    public void onCompleted() {
      completed = true;
    }
  }

  static final class WorkerResult {
    final List<Duration> latencies;
    final List<Long> extprocDurationsNs;
    final List<ExchangeError> errors;

    WorkerResult(
        List<Duration> latencies, List<Long> extprocDurationsNs, List<ExchangeError> errors) {
      this.latencies = latencies;
      this.extprocDurationsNs = extprocDurationsNs;
      this.errors = errors;
    }
  }

  static final class ExchangeError {
    final int index;
    final String error;

    ExchangeError(int index, String error) {
      this.index = index;
      this.error = error;
    }
  }

  static final class ExchangeOutcome {
    final Duration latency;
    final Long extprocDurationNs;
    final String error;

    ExchangeOutcome(Duration latency, Long extprocDurationNs, String error) {
      this.latency = latency;
      this.extprocDurationNs = extprocDurationNs;
      this.error = error;
    }
  }
}
