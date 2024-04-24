package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import io.grpc.Status;
import io.grpc.StatusRuntimeException;
import io.grpc.health.v1.HealthCheckResponse.ServingStatus;
import io.grpc.protobuf.services.HealthStatusManager;
import io.grpc.stub.StreamObserver;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.TreeMap;
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

    return new StreamObserver<ProcessingRequest>() {

      @Override
      public void onNext(ProcessingRequest request) {
        try {
          responseObserver.onNext(processPhase(request, ctx));
          if (ctx.isProcessingComplete()) {
            responseObserver.onCompleted();
          }
        } catch (Throwable t) {
          onError(t);
        }
      }

      @Override
      public void onError(Throwable err) {
        if (err instanceof StatusRuntimeException) {
          StatusRuntimeException sre = (StatusRuntimeException) err;
          if (sre.getStatus().getCode() == Status.CANCELLED.getCode()) {
            logger.fine("Request processing stream cancelled during " + ctx.phase);
          } else {
            logger.severe("Encountered error in processing during " + ctx.phase + ": " + err);
            responseObserver.onError(sre);
          }
        } else {
          logger.severe(
              "Encountered internal error in processing during " + ctx.phase + ": " + err);
          StatusRuntimeException sre =
              Status.INTERNAL.withDescription(err.getMessage()).asRuntimeException();
          responseObserver.onError(sre);
        }
      }

      @Override
      public void onCompleted() {
        logger.fine("Request processing completed during " + ctx.phase);
        responseObserver.onCompleted();
      }
    };
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
        break;
      case REQUEST_BODY:
        String requestBodyString = request.getRequestBody().getBody().toStringUtf8();
        ctx.endOfStream = request.getRequestBody().getEndOfStream();
        processor.processRequestBody(ctx, requestBodyString);
        break;
      case REQUEST_TRAILERS:
        Map<String, String> requestTrailers =
            plainMapFromProtoHeaders(request.getRequestTrailers().getTrailers());
        processor.processRequestTrailers(ctx, requestTrailers);
        break;
      case RESPONSE_HEADERS:
        HeaderMap protoResponseHeaders = request.getResponseHeaders().getHeaders();
        Map<String, String> responseHeaders = ctx.initializeResponse(protoResponseHeaders);
        ctx.endOfStream = request.getResponseHeaders().getEndOfStream();
        processor.processResponseHeaders(ctx, responseHeaders);
        break;
      case RESPONSE_BODY:
        String responseBodyString = request.getResponseBody().getBody().toStringUtf8();
        ctx.endOfStream = request.getResponseBody().getEndOfStream();
        processor.processResponseBody(ctx, responseBodyString);
        break;
      case RESPONSE_TRAILERS:
        Map<String, String> responseTrailers =
            plainMapFromProtoHeaders(request.getResponseTrailers().getTrailers());
        processor.processResponseTrailers(ctx, responseTrailers);
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
