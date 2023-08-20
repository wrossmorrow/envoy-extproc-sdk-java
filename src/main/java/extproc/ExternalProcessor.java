package extproc;

// import io.grpc.stub.ServerCallStreamObserver;
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
import java.util.HashMap;
import java.util.Map;
import java.util.logging.Logger;

public class ExternalProcessor extends ExternalProcessorGrpc.ExternalProcessorImplBase {
  private static final Logger logger = Logger.getLogger(ExternalProcessor.class.getName());

  protected RequestProcessor processor;
  protected String procname;
  protected ProcessingOptions options;
  protected HealthStatusManager health;

  public ExternalProcessor(RequestProcessor processor, HealthStatusManager health) {
    this.processor = processor;
    this.health = health;
    this.processor.setHealthManager(new InternalHealthManager());
    logger.info("Setting up ExternalProcessor with " + processor.getName());
    defineOptions();
  }

  protected void defineOptions() {
    procname = processor.getName();
    options = processor.getOptions();
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
          ;
          if (sre.getStatus().getCode() == Status.CANCELLED.getCode()) {
            responseObserver.onCompleted();
          } else {
            System.out.println("Encountered error in processing: " + err);
            responseObserver.onError(sre);
          }
        } else {
          System.out.println("Encountered error in processing: " + err);
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
        ctx.initialize(requestHeaders);
        addBoilerplateHeaders(ctx);
        if (pr.getRequestHeaders().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processRequestHeaders(ctx, requestHeaders);
        break;
      case REQUEST_BODY:
        String requestBodyString = pr.getRequestBody().getBody().toStringUtf8();
        addBoilerplateHeaders(ctx);
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
        addBoilerplateHeaders(ctx);
        if (pr.getRequestHeaders().getEndOfStream()) {
          ctx.endOfStream = true;
        }
        processor.processResponseHeaders(ctx, responseHeaders);
        break;
      case RESPONSE_BODY:
        String responseBodyString = pr.getResponseBody().getBody().toStringUtf8();
        addBoilerplateHeaders(ctx);
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

    ctx.duration.plus(Duration.between(phaseStarted, Instant.now()));
    return ctx.getResponse(phase); // stops duration timer
  }

  protected void addBoilerplateHeaders(RequestContext ctx) {
    if (options.updateExtProcHeader) {
      ctx.appendHeader("x-extproc-processors", procname);
    }
    if (options.updateDurationHeader) {
      ctx.overwriteHeader("x-extproc-duration", ctx.getDuration().toString());
    }
  }

  protected Map<String, String> plainMapFromProtoHeaders(HeaderMap protoHeaders) {
    Map<String, String> headers = new HashMap<>();
    for (HeaderValue hv : protoHeaders.getHeadersList()) {
      headers.put(hv.getKey(), hv.getValue());
    }
    return headers;
  }
}
