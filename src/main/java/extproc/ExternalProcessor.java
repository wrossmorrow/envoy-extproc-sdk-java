package extproc;

import io.grpc.stub.StreamObserver;
import java.util.Map;
import java.util.HashMap;
import java.util.logging.Logger;

import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;

import extproc.processors.NoOpRequestProcessor;

public class ExternalProcessor extends ExternalProcessorGrpc.ExternalProcessorImplBase {
    private static final Logger logger = Logger.getLogger(ExternalProcessor.class.getName());

    protected RequestProcessor processor;
    protected String procname;
    protected ProcessingOptions options;

    public ExternalProcessor() {
        processor = new NoOpRequestProcessor();
        defineOptions();
    }

    public ExternalProcessor(RequestProcessor processor) {
        this.processor = processor;
        defineOptions();
    }

    protected void defineOptions() {
        procname = processor.getName();
        options = processor.getOptions();
    }
    
    @Override
    public StreamObserver<ProcessingRequest> process(final StreamObserver<ProcessingResponse> responseObserver) {

      RequestContext ctx = new RequestContext();

      return new StreamObserver<ProcessingRequest>() {
        @Override
        public void onNext(ProcessingRequest request) {
          responseObserver.onNext(processPhase(request, ctx));
        }
  
        @Override
        public void onError(Throwable err) {
          System.out.println("Encountered error in processing");
        }
  
        @Override
        public void onCompleted() {
          responseObserver.onCompleted();
        }
      };

    }

    protected ProcessingResponse processPhase(ProcessingRequest pr, RequestContext ctx) {

      RequestCase phase = pr.getRequestCase();

      if (phase != RequestCase.REQUEST_HEADERS) {
        ctx.reset(); // starts duration timer
      }

      if (options.logPhases) {
        logger.info("ExtProc " + procname + " Processing " + phase.toString());
      }

      switch(phase) {
        case REQUEST_HEADERS:
          Map<String, String> requestHeaders = plainMapFromProtoHeaders(pr.getRequestHeaders().getHeaders());
          ctx.initialize(requestHeaders);
          addBoilerplateHeaders(ctx);
          if (pr.getRequestHeaders().getEndOfStream()) {
            ctx.streamIsComplete();
          }
          processor.processRequestHeaders(ctx, requestHeaders);
          break;
        case REQUEST_BODY:
          String requestBodyString = pr.getRequestBody().getBody().toString();
          addBoilerplateHeaders(ctx);
          processor.processRequestBody(ctx, requestBodyString);
          break;
        case REQUEST_TRAILERS:
          Map<String, String> requestTrailers = plainMapFromProtoHeaders(pr.getRequestTrailers().getTrailers());
          processor.processRequestTrailers(ctx, requestTrailers);
          break;
        case RESPONSE_HEADERS:
          Map<String, String> responseHeaders = plainMapFromProtoHeaders(pr.getResponseHeaders().getHeaders());
          addBoilerplateHeaders(ctx);
          if (pr.getRequestHeaders().getEndOfStream()) {
            ctx.streamIsComplete();
          }
          processor.processResponseHeaders(ctx, responseHeaders);
          break;
        case RESPONSE_BODY:
          String responseBodyString = pr.getResponseBody().getBody().toString();
          addBoilerplateHeaders(ctx);
          processor.processResponseBody(ctx, responseBodyString);
          break;
        case RESPONSE_TRAILERS:
          Map<String, String> responseTrailers = plainMapFromProtoHeaders(pr.getResponseTrailers().getTrailers());
          processor.processResponseTrailers(ctx, responseTrailers);
          break;
        default:
          throw new RuntimeException("Unknown request type");
      }

      return ctx.getResponse(phase); // stops duration timer

    }

    protected void addBoilerplateHeaders(RequestContext ctx) {
      if (options.updateExtProcHeader) {
        ctx.appendHeader("x-extproc-seen", procname);
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
