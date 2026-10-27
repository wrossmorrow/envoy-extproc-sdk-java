package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.BodyResponse;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse.ResponseStatus;
import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.HeadersResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.service.ext_proc.v3.TrailersResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

public class ExternalProcessor extends ExternalProcessorGrpc.ExternalProcessorImplBase {
  private static final Logger _logger = Logger.getLogger(ExternalProcessor.class.getName());

  private static final String X_EXTPROC_DURATION_NS = "x-extproc-duration-ns";

  /** internal implementation of status management */
  private class InternalHealthManager implements RequestProcessorHealthManager {

    private HealthStatusManager health;

    public InternalHealthManager(HealthStatusManager health) {
      this.health = health;
    }

    @Override
    public void serving() {
      if (health != null) {
        health.setStatus(ExternalProcessorGrpc.SERVICE_NAME, ServingStatus.SERVING);
      }
    }

    @Override
    public void notServing() {
      if (health != null) {
        health.setStatus(ExternalProcessorGrpc.SERVICE_NAME, ServingStatus.NOT_SERVING);
      }
    }

    @Override
    public void failed() {
      if (health != null) {
        health.enterTerminalState();
      }
    }
  }

  protected RequestProcessor processor;
  protected String procname;
  protected ProcessingOptions options;
  protected HealthStatusManager health;
  protected Logger logger;
  protected Metrics metrics = new Metrics();

  public ExternalProcessor(RequestProcessor processor, HealthStatusManager health) {
    this.processor = processor;
    this.health = health;
    this.logger = _logger;
    this.processor.setHealthManager(new InternalHealthManager(health));
    defineOptions();
  }

  public ExternalProcessor(RequestProcessor processor, HealthStatusManager health, Logger logger) {
    this.processor = processor;
    this.health = health;
    this.logger = logger;
    this.processor.setHealthManager(new InternalHealthManager(health));
    defineOptions();
  }

  protected void defineOptions() {
    procname = processor.getName();
    options = processor.getOptions();
    logger.fine("Setting up ExternalProcessor with " + procname + " and options " + options);
  }

  @Override
  public StreamObserver<ProcessingRequest> process(
      final StreamObserver<ProcessingResponse> responseObserver) {

    RequestContext ctx = new RequestContext();

    metrics.totalStreams.inc();
    metrics.activeStreams.inc();

    return new StreamObserver<ProcessingRequest>() {
      final Instant streamStarted = Instant.now();
      final AtomicBoolean errored = new AtomicBoolean(false);

      @Override
      public void onNext(ProcessingRequest request) {
        try {
          responseObserver.onNext(processPhase(request, ctx));
          if (ctx.isProcessingComplete()) {
            responseObserver.onCompleted();
          }
        } catch (Throwable t) {
          errored.set(true);
          handlePhaseError(ctx, t);
        }
      }

      @Override
      public void onError(Throwable err) {
        if (isStreamCancelled(err)) {
          // the stream was cancelled (client going away mid-stream, envoy aborting); this is a
          // graceful end, not a processing error, but still notify the error handler so the
          // processor can clean up
          notifyErrorHandler(ctx, err);
          logger.fine("Request processing stream cancelled during " + ctx.getPhaseName());
          finalizeStreamMetrics(false);
          // do not propagate the cancellation as a stream error
          return;
        }
        // a genuine error: notify the error handler, then surface it to the response observer
        errored.set(true);
        notifyErrorHandler(ctx, err);
        if (err instanceof StatusRuntimeException) {
          StatusRuntimeException sre = (StatusRuntimeException) err;
          logger.severe(
              "Encountered error in processing during " + ctx.getPhaseName() + ": " + err);
          responseObserver.onError(sre);
        } else {
          logger.severe(
              "Encountered internal error in processing during " + ctx.getPhaseName() + ": " + err);
          StatusRuntimeException sre =
              Status.INTERNAL.withDescription(err.getMessage()).asRuntimeException();
          responseObserver.onError(sre);
        }
        finalizeStreamMetrics(true);
      }

      @Override
      public void onCompleted() {
        logger.fine("Request processing completed during " + ctx.getPhaseName());
        finalizeStreamMetrics(errored.get());
        responseObserver.onCompleted();
      }

      /**
       * Finalize per-stream metrics: decrement the active stream gauge, observe the stream and
       * external-processing durations, and account for errored streams.
       */
      protected void finalizeStreamMetrics(boolean errored) {
        metrics.activeStreams.dec();
        metrics.streamDurationSeconds.observe(
            Duration.between(streamStarted, Instant.now()).toSeconds());
        metrics.externalProcessingSeconds.observe(ctx.getDuration().toSeconds());
        if (errored) {
          metrics.erroredStreams.inc();
        }
      }

      /**
       * Handles an error thrown while processing a phase: notifies the processor's error handler
       * and then either aborts the stream (per the processor's abort policy) or sends an empty
       * continue response and keeps the stream alive.
       */
      protected void handlePhaseError(RequestContext ctx, Throwable err) {
        metrics.phaseErrors.inc();
        notifyErrorHandler(ctx, err);
        if (options.abortOnProcessorFailure) {
          StatusRuntimeException status =
              Status.UNKNOWN
                  .withDescription("processor failed and abort requested: " + err.getMessage())
                  .asRuntimeException();
          responseObserver.onError(status);
          return;
        }
        // without aborting, send an empty continue response and keep the stream alive
        responseObserver.onNext(emptyContinueResponse(ctx));
        metrics.totalEmptyResponses.inc();
      }

      /**
       * Notifies the processor's error handler; a throwing handler must not take down the stream.
       */
      protected void notifyErrorHandler(RequestContext ctx, Throwable err) {
        try {
          processor.errorHandler(ctx, ctx.getPhase(), err);
        } catch (Throwable handlerErr) {
          logger.severe(
              "ErrorHandler itself threw during processing of phase "
                  + ctx.getPhaseName()
                  + ": "
                  + handlerErr);
        }
      }

      /** Whether the error is a stream cancellation rather than a processing failure. */
      protected boolean isStreamCancelled(Throwable err) {
        if (err == null) {
          return false;
        }
        if (err instanceof StatusRuntimeException) {
          return ((StatusRuntimeException) err).getStatus().getCode() == Status.CANCELLED.getCode();
        }
        return err instanceof CancellationException;
      }
    };
  }

  /** Build an empty (continue) processing response for the given phase; never returns null. */
  protected ProcessingResponse emptyContinueResponse(RequestContext ctx) {
    final CommonResponse common =
        CommonResponse.newBuilder().setStatus(ResponseStatus.CONTINUE).build();
    switch (ctx.getPhase()) {
      case REQUEST_HEADERS:
        return ProcessingResponse.newBuilder()
            .setRequestHeaders(HeadersResponse.newBuilder().setResponse(common).build())
            .build();
      case REQUEST_BODY:
        return ProcessingResponse.newBuilder()
            .setRequestBody(BodyResponse.newBuilder().setResponse(common).build())
            .build();
      case REQUEST_TRAILERS:
        return ProcessingResponse.newBuilder()
            .setRequestTrailers(
                TrailersResponse.newBuilder()
                    .setHeaderMutation(HeaderMutation.newBuilder().build())
                    .build())
            .build();
      case RESPONSE_HEADERS:
        return ProcessingResponse.newBuilder()
            .setResponseHeaders(HeadersResponse.newBuilder().setResponse(common).build())
            .build();
      case RESPONSE_BODY:
        return ProcessingResponse.newBuilder()
            .setResponseBody(BodyResponse.newBuilder().setResponse(common).build())
            .build();
      case RESPONSE_TRAILERS:
        return ProcessingResponse.newBuilder()
            .setResponseTrailers(
                TrailersResponse.newBuilder()
                    .setHeaderMutation(HeaderMutation.newBuilder().build())
                    .build())
            .build();
      default:
        throw new RuntimeException("unknown request phase");
    }
  }

  protected ProcessingResponse processPhase(ProcessingRequest request, RequestContext ctx) {

    final RequestCase phase = request.getRequestCase();
    final Instant phaseStarted = Instant.now();

    logger.fine("" + procname + " Processing " + phase.toString());
    if (options.logPhases) {
      logger.info("" + procname + " Processing " + phase.toString());
    }

    ctx.reset(phase);
    switch (phase) {
      case REQUEST_HEADERS:
        HeaderMap protoRequestHeaders = request.getRequestHeaders().getHeaders();
        Map<String, String> requestHeaders = ctx.initializeRequest(protoRequestHeaders);
        ctx.endOfStream = request.getRequestHeaders().getEndOfStream();
        processor.processRequestHeaders(ctx, requestHeaders);
        recordPhaseMetrics(phase, 0, 0);
        break;
      case REQUEST_BODY:
        String requestBodyString = request.getRequestBody().getBody().toStringUtf8();
        ctx.endOfStream = request.getRequestBody().getEndOfStream();
        processor.processRequestBody(ctx, requestBodyString);
        recordPhaseMetrics(phase, request.getRequestBody().getBody().size(), 0);
        break;
      case REQUEST_TRAILERS:
        Map<String, String> requestTrailers =
            plainMapFromProtoHeaders(request.getRequestTrailers().getTrailers());
        processor.processRequestTrailers(ctx, requestTrailers);
        recordPhaseMetrics(phase, 0, 0);
        break;
      case RESPONSE_HEADERS:
        HeaderMap protoResponseHeaders = request.getResponseHeaders().getHeaders();
        Map<String, String> responseHeaders = ctx.initializeResponse(protoResponseHeaders);
        ctx.endOfStream = request.getResponseHeaders().getEndOfStream();
        processor.processResponseHeaders(ctx, responseHeaders);
        if (options.updateExtProcHeader) {
          ctx.appendHeader("x-extproc-names", procname);
        }
        recordPhaseMetrics(phase, 0, 0);
        break;
      case RESPONSE_BODY:
        String responseBodyString = request.getResponseBody().getBody().toStringUtf8();
        ctx.endOfStream = request.getResponseBody().getEndOfStream();
        processor.processResponseBody(ctx, responseBodyString);
        recordPhaseMetrics(phase, 0, request.getResponseBody().getBody().size());
        break;
      case RESPONSE_TRAILERS:
        Map<String, String> responseTrailers =
            plainMapFromProtoHeaders(request.getResponseTrailers().getTrailers());
        processor.processResponseTrailers(ctx, responseTrailers);
        recordPhaseMetrics(phase, 0, 0);
        break;
      default:
        throw new RuntimeException("Unknown processing request type in " + phase);
    }

    // we're not capturing response serialization time here, but we can't include
    // info about that in the headers we might include anyway (circularity).
    ctx.updateDuration(phase, Duration.between(phaseStarted, Instant.now()));
    switch (phase) {
      case REQUEST_HEADERS:
      case REQUEST_BODY:
      case REQUEST_TRAILERS:
        if (options.upstreamDurationHeader) {
          logger.fine("Adding upstream duration headers");
          addUpstreamExtProcHeaders(ctx);
        }
        break;
      case RESPONSE_HEADERS:
      case RESPONSE_BODY:
      case RESPONSE_TRAILERS:
        if (options.updateDurationHeader) {
          // the external processing duration is reported on end-of-stream responses only
          if (ctx.streamComplete()) {
            ctx.appendHeader(X_EXTPROC_DURATION_NS, String.valueOf(ctx.duration.toNanos()));
          }
        }
        if (options.downstreamDurationHeader) {
          logger.fine("Adding downstream duration headers");
          addDownstreamExtProcHeaders(ctx);
        }
        break;
      default:
        break;
    }

    return ctx.getResponse(phase);
  }

  /** Record per-phase processing metrics for the given phase and body byte counts. */
  protected void recordPhaseMetrics(RequestCase phase, long receivedBytes, long returnedBytes) {
    switch (phase) {
      case REQUEST_HEADERS:
        metrics.totalRequestHeaders.inc();
        break;
      case REQUEST_BODY:
        metrics.totalRequestBody.inc();
        metrics.requestBodyBytes.inc(receivedBytes);
        break;
      case REQUEST_TRAILERS:
        metrics.totalRequestTrailers.inc();
        break;
      case RESPONSE_HEADERS:
        metrics.totalResponseHeaders.inc();
        break;
      case RESPONSE_BODY:
        metrics.totalResponseBody.inc();
        metrics.responseBodyBytes.inc(returnedBytes);
        break;
      case RESPONSE_TRAILERS:
        metrics.totalResponseTrailers.inc();
        break;
      default:
        break;
    }
  }

  protected void addUpstreamExtProcHeaders(RequestContext ctx) {
    final String existing = ctx.requestHeaders.getOrDefault(X_EXTPROC_DURATION_NS, "");
    final Long nanos = ctx.duration.toNanos();
    ctx.overwriteHeader(X_EXTPROC_DURATION_NS, durationHeaderValue(existing, nanos));
  }

  protected void addDownstreamExtProcHeaders(RequestContext ctx) {
    final String existing = ctx.responseHeaders.getOrDefault(X_EXTPROC_DURATION_NS, "");
    final Long nanos = ctx.duration.toNanos();
    ctx.overwriteHeader(X_EXTPROC_DURATION_NS, durationHeaderValue(existing, nanos));
  }

  /** return an appended/updated duration header; never returns null */
  protected String durationHeaderValue(String existing, Long nanos) {
    final String current = procname + "=" + String.valueOf(nanos);
    if (existing.isEmpty()) {
      return current;
    }
    String[] timedSections = existing.split(",");
    if (timedSections.length == 0) {
      return current;
    }
    for (int i = 0; i < timedSections.length; i++) {
      if (timedSections[i].startsWith(procname)) {
        timedSections[i] = current;
        return String.join(",", timedSections);
      }
    }
    return existing + "," + current;
  }

  /** case-insensitive keyed map of headers; never returns null */
  protected static Map<String, String> plainMapFromProtoHeaders(HeaderMap protoHeaders) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    if (protoHeaders == null) {
      return headers;
    }
    for (HeaderValue hv : protoHeaders.getHeadersList()) {
      headers.put(hv.getKey(), hv.getValue());
    }
    return headers;
  }
}
