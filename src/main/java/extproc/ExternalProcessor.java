package extproc;

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

  protected ProcessingResponse processPhase(ProcessingRequest pr, RequestContext ctx) {

    RequestCase phase = pr.getRequestCase();
    final Instant phaseStarted = Instant.now();

    if (phase != RequestCase.REQUEST_HEADERS) {
      ctx.reset();
    }

    if (options.logPhases) {
      logger.info("ExtProc " + procname + " Processing " + phase.toString());
    }

    switch (phase) {
      case REQUEST_HEADERS:
        Map<String, String> requestHeaders =
            plainMapFromProtoHeaders(pr.getRequestHeaders().getHeaders());
        ctx.initializeRequest(requestHeaders);
        addUpstreamExtProcHeaders(ctx);
        if (pr.getRequestHeaders().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processRequestHeaders(ctx, requestHeaders);
        break;
      case REQUEST_BODY:
        String requestBodyString = pr.getRequestBody().getBody().toStringUtf8();
        addUpstreamExtProcHeaders(ctx);
        if (pr.getRequestBody().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processRequestBody(ctx, requestBodyString);
        break;
      case REQUEST_TRAILERS:
        Map<String, String> requestTrailers =
            plainMapFromProtoHeaders(pr.getRequestTrailers().getTrailers());
        processor.processRequestTrailers(ctx, requestTrailers);
        break;
      case RESPONSE_HEADERS:
        Map<String, String> responseHeaders =
            plainMapFromProtoHeaders(pr.getResponseHeaders().getHeaders());
        ctx.initializeResponse(responseHeaders);
        addDownstreamExtProcHeaders(ctx);
        if (pr.getRequestHeaders().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processResponseHeaders(ctx, responseHeaders);
        break;
      case RESPONSE_BODY:
        String responseBodyString = pr.getResponseBody().getBody().toStringUtf8();
        addDownstreamExtProcHeaders(ctx);
        if (pr.getResponseBody().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processResponseBody(ctx, responseBodyString);
        break;
      case RESPONSE_TRAILERS:
        Map<String, String> responseTrailers =
            plainMapFromProtoHeaders(pr.getResponseTrailers().getTrailers());
        processor.processResponseTrailers(ctx, responseTrailers);
        break;
      default:
        throw new RuntimeException("Unknown request type");
    }

    ctx.updateDuration(phase, Duration.between(phaseStarted, Instant.now()));
    return ctx.getResponse(phase); // stops duration timer
  }

  protected void addUpstreamExtProcHeaders(RequestContext ctx) {
    if (options.upstreamDurationHeader) {
      final String existing = ctx.requestHeaders.getOrDefault("x-extproc-duration-ns", "");
      final Long nanos = ctx.duration.toNanos();
      ctx.addHeader("x-extproc-duration-ns", durationHeaderValue(existing, nanos));
    }
  }

  protected void addDownstreamExtProcHeaders(RequestContext ctx) {
    if (options.downstreamDurationHeader) {
      final String existing = ctx.responseHeaders.getOrDefault("x-extproc-duration-ns", "");
      final Long nanos = ctx.duration.toNanos();
      ctx.addHeader("x-extproc-duration-ns", durationHeaderValue(existing, nanos));
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
