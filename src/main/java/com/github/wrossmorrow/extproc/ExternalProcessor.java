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

  protected RequestProcessor processor;
  protected String procname;
  protected ProcessingOptions options;
  protected HealthStatusManager health;
  protected Logger logger;

  public ExternalProcessor(RequestProcessor processor, HealthStatusManager health) {
    this.processor = processor;
    this.health = health;
    this.logger = _logger;
    this.processor.setHealthManager(new InternalHealthManager());
    defineOptions();
  }

  public ExternalProcessor(RequestProcessor processor, HealthStatusManager health, Logger logger) {
    this.processor = processor;
    this.health = health;
    this.logger = logger;
    this.processor.setHealthManager(new InternalHealthManager());
    defineOptions();
  }

  protected void defineOptions() {
    procname = processor.getName();
    options = processor.getOptions();
    logger.info("Setting up ExternalProcessor with " + procname + " and options " + options);
  }

  /*
   * Health status management
   */
  public class InternalHealthManager implements RequestProcessorHealthManager {

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

  @Override
  public StreamObserver<ProcessingRequest> process(
      final StreamObserver<ProcessingResponse> responseObserver) {

    RequestContext ctx = new RequestContext();

    return new StreamObserver<ProcessingRequest>() {
      @Override
      public void onNext(ProcessingRequest request) {
        responseObserver.onNext(processPhase(request, ctx));
      }

      @Override
      public void onError(Throwable err) {
        if (err instanceof StatusRuntimeException) {
          StatusRuntimeException sre = (StatusRuntimeException) err;
          if (sre.getStatus().getCode() == Status.CANCELLED.getCode()) {
            logger.fine("Processing stream cancelled");
            responseObserver.onCompleted();
          } else {
            logger.severe("Encountered error in processing: " + err);
            System.err.println("Encountered error in processing: " + err);
            responseObserver.onError(sre);
          }
        } else {
          logger.severe("Encountered error in processing: " + err);
          System.err.println("Encountered error in processing: " + err);
          StatusRuntimeException sre =
              Status.INTERNAL.withDescription(err.getMessage()).asRuntimeException();
          responseObserver.onError(sre);
        }
      }

      @Override
      public void onCompleted() {
        responseObserver.onCompleted();
      }
    };
  }

  protected ProcessingResponse processPhase(ProcessingRequest request, RequestContext ctx) {

    final RequestCase phase = request.getRequestCase();
    final Instant phaseStarted = Instant.now();

    if (phase != RequestCase.REQUEST_HEADERS) {
      ctx.reset();
    }

    if (options.logPhases) {
      logger.info("ExtProc " + procname + " Processing " + phase.toString());
    }

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
        throw new RuntimeException("Unknown request type");
    }

    // we're not capturing response serialization time here, but we can't include
    // info about that in the headers we might include anyway (circularity).
    ctx.updateDuration(phase, Duration.between(phaseStarted, Instant.now()));
    switch (phase) {
      case REQUEST_HEADERS:
      case REQUEST_BODY:
      case REQUEST_TRAILERS:
        addUpstreamExtProcHeaders(ctx);
        break;
      case RESPONSE_HEADERS:
      case RESPONSE_BODY:
      case RESPONSE_TRAILERS:
        addDownstreamExtProcHeaders(ctx);
        break;
      default:
        break;
    }

    return ctx.getResponse(phase);
  }

  protected void addUpstreamExtProcHeaders(RequestContext ctx) {
    if (options.upstreamDurationHeader) {
      final String existing = ctx.requestHeaders.getOrDefault("x-extproc-duration-ns", "");
      final Long nanos = ctx.duration.toNanos();
      ctx.overwriteHeader("x-extproc-duration-ns", durationHeaderValue(existing, nanos));
    }
  }

  protected void addDownstreamExtProcHeaders(RequestContext ctx) {
    if (options.downstreamDurationHeader) {
      final String existing = ctx.responseHeaders.getOrDefault("x-extproc-duration-ns", "");
      final Long nanos = ctx.duration.toNanos();
      ctx.overwriteHeader("x-extproc-duration-ns", durationHeaderValue(existing, nanos));
    }
  }

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

  protected Map<String, String> plainMapFromProtoHeaders(HeaderMap protoHeaders) {
    Map<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (HeaderValue hv : protoHeaders.getHeadersList()) {
      headers.put(hv.getKey(), hv.getValue());
    }
    return headers;
  }
}
