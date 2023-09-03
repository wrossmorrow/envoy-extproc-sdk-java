package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.BodyMutation;
import build.buf.gen.envoy.service.ext_proc.v3.BodyResponse;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse.ResponseStatus;
import build.buf.gen.envoy.service.ext_proc.v3.HeaderMutation;
import build.buf.gen.envoy.service.ext_proc.v3.HeadersResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ImmediateResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.service.ext_proc.v3.TrailersResponse;
import build.buf.gen.envoy.type.v3.HttpStatus;
import build.buf.gen.envoy.type.v3.StatusCode;
import com.google.protobuf.ByteString;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class RequestContext {

  protected Instant started;
  protected Duration duration;
  protected Long[] phaseDurations;
  protected String scheme;
  protected String authority;
  protected String method;
  protected String path;
  protected String queryString;
  protected Map<String, List<String>> params;
  protected int status;
  protected String requestId;
  protected String processorId;
  protected Map<String, String> requestHeaders;
  protected Map<String, String> responseHeaders;
  protected boolean endOfStream;
  protected List<HeaderValueOption> addHeaders;
  protected List<String> removeHeaders;

  protected Boolean finished;
  protected Boolean replace;
  protected Boolean cancelled;
  BodyMutation bodyMutation;
  ImmediateResponse immediateResponse;

  public RequestContext() {
    status = 0;
    started = Instant.now();
    duration = Duration.between(this.started, Instant.now());
    phaseDurations = new Long[6];
    for (int i = 0; i < phaseDurations.length; i++) {
      phaseDurations[i] = 0L;
    }
    reset();
  }

  protected void reset() {
    cancelled = false;
    replace = false;
    finished = false;
    endOfStream = false;
    addHeaders = new ArrayList<HeaderValueOption>();
    removeHeaders = new ArrayList<String>();
    bodyMutation = BodyMutation.newBuilder().build();
  }

  // protected void initializeRequest(Map<String, String> headers) {
  // for (Map.Entry<String, String> entry : headers.entrySet()) {
  protected Map<String, String> initializeRequest(HeaderMap protoHeaders) {
    requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (HeaderValue hv : protoHeaders.getHeadersList()) {
      final String key = hv.getKey();
      final String value = hv.getValue();
      if (key.startsWith(":")) {
        switch (key) {
          case ":scheme":
            scheme = value;
            break;
          case ":authority":
            authority = value;
            break;
          case ":method":
            method = value;
            break;
          case ":path":
            parsePath(value);
            break;
          default:
            break;
        }
      } else {
        switch (key) {
          case "x-request-id":
            requestId = value;
            break;
          default:
            requestHeaders.put(key, value);
            break;
        }
      }
    }
    return requestHeaders;
  }

  protected void parsePath(String rawPath) {
    path = rawPath;
    if (path.contains("?")) {
      String[] parts = path.split("\\?");
      path = parts[0];
      if (parts.length > 1) {
        queryString = URLDecoder.decode(parts[1], StandardCharsets.UTF_8);
        parseQueryParams(queryString);
      }
    }
  }

  protected void parseQueryParams(String decodedQuery) {
    if (decodedQuery == null || decodedQuery.isEmpty()) {
      return;
    }
    params = new HashMap<String, List<String>>();
    for (String param : decodedQuery.split("&")) {
      String[] keyValuePair = param.split("=");
      String key = keyValuePair[0];
      String value = keyValuePair.length == 1 ? "" : keyValuePair[1];
      if (!params.containsKey(key)) {
        params.put(key, new ArrayList<String>());
      }
      if (value != null && !value.isEmpty()) {
        params.get(key).add(value);
      }
    }
  }

  protected Map<String, String> initializeResponse(HeaderMap headers) {
    responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    for (HeaderValue hv : headers.getHeadersList()) {
      final String key = hv.getKey();
      final String value = hv.getValue();
      if (key.startsWith(":")) {
        switch (key) {
          case ":status":
            status = Integer.parseInt(value);
            break;
          default:
            break;
        }
      } else {
        responseHeaders.put(key, value);
      }
    }
    return responseHeaders;
  }

  protected void updateDuration(RequestCase phase, Duration duration) {
    // technically, phase.getNumber()-2 would work, but this is more readable
    // and doesn't depend on the generated code for the derived Enum RequestCase
    Long nanos = duration.toNanos();
    switch (phase) {
      case REQUEST_HEADERS:
        phaseDurations[0] = nanos;
        break;
      case REQUEST_BODY:
        phaseDurations[1] = nanos;
        break;
      case REQUEST_TRAILERS:
        phaseDurations[2] = nanos;
        break;
      case RESPONSE_HEADERS:
        phaseDurations[3] = nanos;
        break;
      case RESPONSE_BODY:
        phaseDurations[4] = nanos;
        break;
      case RESPONSE_TRAILERS:
        phaseDurations[5] = nanos;
        break;
      default:
        break;
    }
    this.duration.plus(duration);
  }

  public Map<String, String> getRequestHeaders() {
    return requestHeaders;
  }

  public String getRequestHeader(String name) {
    return requestHeaders.get(name);
  }

  public String getRequestHeaderOrDefault(String name, String orValue) {
    return requestHeaders.getOrDefault(name, orValue);
  }

  public Map<String, String> getResponseHeaders() {
    return responseHeaders;
  }

  public String getResponseHeader(String name) {
    return responseHeaders.get(name);
  }

  public String getResponseHeaderOrDefault(String name, String orValue) {
    return responseHeaders.getOrDefault(name, orValue);
  }

  public String getScheme() {
    return scheme;
  }

  public String getDomain() {
    return authority;
  }

  public String getAuthority() {
    return authority;
  }

  public String getMethod() {
    return method;
  }

  public String getPath() {
    return path;
  }

  public String getQueryString() {
    if (queryString == null) {
      return "";
    }
    return queryString;
  }

  public Map<String, List<String>> getParams() {
    if (params == null) {
      return new HashMap<String, List<String>>();
    }
    return params;
  }

  public int getStatus() {
    return status;
  }

  public String getRequestId() {
    return requestId;
  }

  public Instant getStarted() {
    return started;
  }

  public Duration getDuration() {
    return duration;
  }

  public Boolean streamComplete() {
    return endOfStream;
  }

  public String getProcessorId() {
    return processorId;
  }

  public void setProcessorId(String processorId) {
    if (this.processorId != null) {
      this.processorId = processorId;
    }
  }

  public void continueAndReplace() {
    replace = true;
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
    immediateResponse =
        ImmediateResponse.newBuilder()
            .setStatus(HttpStatus.newBuilder().setCode(StatusCode.forNumber(status)).build())
            .setHeaders(
                HeaderMutation.newBuilder()
                    .addAllSetHeaders(addHeaders)
                    .addAllRemoveHeaders(removeHeaders)
                    .build())
            .setBody(body)
            .build();
  }

  public ProcessingResponse getResponse(RequestCase phase) {

    if (cancelled) {
      return ProcessingResponse.newBuilder().setImmediateResponse(immediateResponse).build();
    }

    switch (phase) {
      case REQUEST_HEADERS:
        return ProcessingResponse.newBuilder().setRequestHeaders(prepareHeadersResponse()).build();
      case REQUEST_BODY:
        return ProcessingResponse.newBuilder().setRequestBody(prepareBodyResponse()).build();
      case REQUEST_TRAILERS:
        return ProcessingResponse.newBuilder()
            .setRequestTrailers(prepareTrailersResponse())
            .build();
      case RESPONSE_HEADERS:
        return ProcessingResponse.newBuilder().setResponseHeaders(prepareHeadersResponse()).build();
      case RESPONSE_BODY:
        return ProcessingResponse.newBuilder().setResponseBody(prepareBodyResponse()).build();
      case RESPONSE_TRAILERS:
        return ProcessingResponse.newBuilder()
            .setResponseTrailers(prepareTrailersResponse())
            .build();
      default:
        throw new RuntimeException("unknown request phase");
    }
  }

  protected HeadersResponse prepareHeadersResponse() {
    return HeadersResponse.newBuilder()
        .setResponse(
            CommonResponse.newBuilder()
                .setStatus(replace ? ResponseStatus.CONTINUE_AND_REPLACE : ResponseStatus.CONTINUE)
                .setHeaderMutation(
                    HeaderMutation.newBuilder()
                        .addAllSetHeaders(addHeaders)
                        .addAllRemoveHeaders(removeHeaders)
                        .build())
                .setBodyMutation(bodyMutation)
                .build())
        .build();
  }

  protected BodyResponse prepareBodyResponse() {
    return BodyResponse.newBuilder()
        .setResponse(
            CommonResponse.newBuilder()
                .setStatus(replace ? ResponseStatus.CONTINUE_AND_REPLACE : ResponseStatus.CONTINUE)
                .setHeaderMutation(
                    HeaderMutation.newBuilder()
                        .addAllSetHeaders(addHeaders)
                        .addAllRemoveHeaders(removeHeaders)
                        .build())
                .setBodyMutation(bodyMutation)
                .build())
        .build();
  }

  protected TrailersResponse prepareTrailersResponse() {
    return TrailersResponse.newBuilder()
        .setHeaderMutation(
            HeaderMutation.newBuilder()
                .addAllSetHeaders(addHeaders)
                .addAllRemoveHeaders(removeHeaders)
                .build())
        .build();
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

  public void removeHeader(String name) {
    removeHeaders.add(name);
  }

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
    for (String header : headers) {
      this.removeHeader(header);
    }
  }

  public void updateHeader(String name, String value, String action) {
    addHeaders.add(
        HeaderValueOption.newBuilder()
            .setHeader(
                build.buf.gen.envoy.config.core.v3.HeaderValue.newBuilder()
                    .setKey(name)
                    .setValue(value)
                    .build())
            .setAppendAction(HeaderValueOption.HeaderAppendAction.valueOf(action))
            .build());
  }

  public void updateHeaders(Map<String, String> headers, String action) {
    for (Map.Entry<String, String> entry : headers.entrySet()) {
      this.updateHeader(entry.getKey(), entry.getValue(), action);
    }
  }

  public void replaceBodyChunk(byte[] body) {
    bodyMutation = BodyMutation.newBuilder().setBody(ByteString.copyFrom(body)).build();
  }

  public void replaceBodyChunk(String body) {
    bodyMutation = BodyMutation.newBuilder().setBody(ByteString.copyFromUtf8(body)).build();
  }

  public void clearBodyChunk() {
    bodyMutation = BodyMutation.newBuilder().setClearBody(true).build();
  }
}
