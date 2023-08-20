package extproc;

import java.time.Instant;
import java.time.Duration;
import java.util.List;
import java.util.ArrayList;
import java.util.Map;
import com.google.protobuf.ByteString;

import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.BodyMutation;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse.ResponseStatus;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.HeadersResponse;
import build.buf.gen.envoy.service.ext_proc.v3.BodyResponse;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ImmediateResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.service.ext_proc.v3.TrailersResponse;
import build.buf.gen.envoy.type.v3.HttpStatus;
import build.buf.gen.envoy.type.v3.StatusCode;

public class RequestContext {
    protected Instant started;
    protected Duration duration;
    protected String scheme;
    protected String authority;
    protected String method;
    protected String path;
    protected String requestId;
    protected String processorId;
    protected Map<String, String> requestHeaders;
    protected boolean endOfStream;

    protected List<HeaderValueOption> addHeaders;
    protected List<String> removeHeaders;

    protected Boolean finished;
    protected Boolean cancelled;
    BodyMutation bodyMutation;
    CommonResponse commonResponse;
    ImmediateResponse immediateResponse;

    public RequestContext() {
        started = Instant.now();
        duration = Duration.between(this.started, Instant.now());
        reset();
    }

    public void initialize(Map<String, String> headers) {
        requestHeaders = headers;
        scheme = headers.get(":scheme");
        authority = headers.get(":authority");
        method = headers.get(":method");
        path = headers.get(":path");
        requestId = headers.get("x-request-id");
    }

    public Map<String, String> getRequestHeaders() { return requestHeaders; }
    public String getScheme() { return scheme; }
    public String getAuthority() { return authority; }
    public String getMethod() { return method; }
    public String getPath() { return path; }
    public String getRequestId() { return requestId; }
    public Instant getStarted() { return started; }
    public Duration getDuration() { return duration; }

    public Boolean streamComplete() { return endOfStream; }
    public void streamIsComplete() { endOfStream = true; } // public, but don't call

    public String getProcessorId() { return processorId; }
    public void setProcessorId(String processorId) {
        if (this.processorId != null) {
            this.processorId = processorId;
        }
    }

    public void reset() {
        cancelled = false;
        finished = false;
        endOfStream = false;
        addHeaders = new ArrayList<HeaderValueOption>();
        removeHeaders = new ArrayList<String>();
        bodyMutation = BodyMutation.newBuilder().build();
    }
    
    public void continueRequest() {
        commonResponse = CommonResponse.newBuilder()
            .setStatus(ResponseStatus.CONTINUE)
            .setHeaderMutation(
                HeaderMutation.newBuilder()
                    .addAllSetHeaders(addHeaders)
                    .addAllRemoveHeaders(removeHeaders)
                    .build()
            )
            .setBodyMutation(bodyMutation)
            .build();
        finishRequest();
    }

    public void continueAndReplace() {
        commonResponse = CommonResponse.newBuilder()
            .setStatus(ResponseStatus.CONTINUE_AND_REPLACE)
            .setHeaderMutation(
                HeaderMutation.newBuilder()
                    .addAllSetHeaders(addHeaders)
                    .addAllRemoveHeaders(removeHeaders)
                    .build()
            )
            .setBodyMutation(bodyMutation)
            .build();
        finishRequest();
    }

    public void cancelRequest(int status) {
        cancelRequest(status, null, "");
    }

    public void cancelRequest(int status, Map<String, String> headers) {
        cancelRequest(status, headers, "");
    }

    public void cancelRequest(int status, String body) {
        cancelRequest(status, null, body);
    }

    public void cancelRequest(int status, Map<String, String> headers, String body) {
        cancelled = true;
        if (headers != null) {
            appendHeaders(headers);
        }
        immediateResponse = ImmediateResponse.newBuilder()
            .setStatus(HttpStatus.newBuilder()
                .setCode(StatusCode.forNumber(status))
                .build()
            )
            .setHeaders(HeaderMutation.newBuilder()
                .addAllSetHeaders(addHeaders)
                .addAllRemoveHeaders(removeHeaders)
                .build()
            )
            .setBody(body)
            .build();
        finishRequest();
    }

    private void finishRequest() {
        finished = true;
    }
    
    public ProcessingResponse getResponse(RequestCase phase) {
        duration = Duration.between(started, Instant.now());

        if (cancelled) {
            return ProcessingResponse.newBuilder()
                .setImmediateResponse(immediateResponse)
                .build();
        }

        if (!finished) {
            continueRequest();
        }

        switch (phase) {
            case REQUEST_HEADERS:
                return ProcessingResponse.newBuilder()
                    .setRequestHeaders(
                        HeadersResponse.newBuilder()
                            .setResponse(commonResponse)
                    )
                    .build();
            case REQUEST_BODY:
                return ProcessingResponse.newBuilder()
                    .setRequestBody(
                        BodyResponse.newBuilder()
                            .setResponse(commonResponse)
                    )
                    .build();
            case REQUEST_TRAILERS:
                return ProcessingResponse.newBuilder()
                    .setRequestTrailers(
                        TrailersResponse.newBuilder()
                            .setHeaderMutation(
                                HeaderMutation.newBuilder()
                                    .addAllSetHeaders(addHeaders)
                                    .addAllRemoveHeaders(removeHeaders)
                                    .build()
                            )
                    )
                    .build();
            case RESPONSE_HEADERS:
                return ProcessingResponse.newBuilder()
                    .setResponseHeaders(
                        HeadersResponse.newBuilder()
                            .setResponse(commonResponse)
                    )
                    .build();
            case RESPONSE_BODY:
                return ProcessingResponse.newBuilder()
                    .setResponseBody(
                        BodyResponse.newBuilder()
                            .setResponse(commonResponse)
                    )
                    .build();
            case RESPONSE_TRAILERS:
                return ProcessingResponse.newBuilder()
                    .setResponseTrailers(
                        TrailersResponse.newBuilder()
                            .setHeaderMutation(
                                HeaderMutation.newBuilder()
                                    .addAllSetHeaders(addHeaders)
                                    .addAllRemoveHeaders(removeHeaders)
                                    .build()
                            )
                    )
                    .build();
            default:
                throw new RuntimeException("unknown request phase");
        }
    }

    public void appendHeader(String name, String value) {
        this.updateHeader(name, value, "APPEND_IF_EXISTS_OR_ADD");
    }

    public void addHeader(String name, String value) {
        this.updateHeader(name, value, "ADD_IF_ABSENT");
    }

    public void overwriteHeader(String name, String value) {
        this.updateHeader(name, value, "OVERWRITE_IF_EXISTS_OR_ADD");
    }

    public void removeHeader(String name) { removeHeaders.add(name); }

    public void appendHeaders(Map<String, String> headers) {
        this.updateHeaders(headers, "APPEND_IF_EXISTS_OR_ADD");
    }

    public void addHeaders(Map<String, String> headers) {
        this.updateHeaders(headers, "ADD_IF_ABSENT");
    }

    public void overwriteHeaders(Map<String, String> headers) {
        this.updateHeaders(headers, "OVERWRITE_IF_EXISTS_OR_ADD");
    }

    public void removeHeaders(List<String> headers) {
        for (String header : headers) { this.removeHeader(header); }
    }

    public void updateHeader(String name, String value, String action) {
        if (finished) {
            throw new RuntimeException("cannot update headers after request is finished");
        }
        addHeaders.add(HeaderValueOption.newBuilder()
            .setHeader(
                build.buf.gen.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey(name)
                    .setValue(value)
                    .build()
            )
            .setAppendAction(HeaderValueOption.HeaderAppendAction.valueOf(action))
            .build()
        );
    }

    public void updateHeaders(Map<String, String> headers, String action) {
        if (finished) {
            throw new RuntimeException("cannot update headers after request is finished");
        }
        for (Map.Entry<String, String> entry : headers.entrySet()) {
            this.updateHeader(entry.getKey(), entry.getValue(), action);
        }
    }
    
    public void replaceBodyChunk(byte[] body) {
        if (finished) {
            throw new RuntimeException("cannot replace body (chunk) after request is finished");
        }
        bodyMutation = BodyMutation.newBuilder()
            .setBody(ByteString.copyFrom(body))
            .build();
    }

    public void replaceBodyChunk(String body) {
        if (finished) {
            throw new RuntimeException("cannot replace body (chunk) after request is finished");
        }
        bodyMutation = BodyMutation.newBuilder()
            .setBody(ByteString.copyFromUtf8(body))
            .build();
    }
    
    public void clearBodyChunk() {
        if (finished) {
            throw new RuntimeException("cannot clear body (chunk) after request is finished");
        }
        bodyMutation = BodyMutation.newBuilder()
            .setClearBody(true)
            .build();
    }
}
