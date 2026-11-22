package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.config.core.v3.Metadata;
import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
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
import com.google.protobuf.Any;
import com.google.protobuf.BoolValue;
import com.google.protobuf.ByteString;
import com.google.protobuf.Struct;
import com.google.protobuf.Value;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Base64;
import java.util.Collections;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.Consumer;

public class RequestContext {

  private static final Base64.Encoder encoder = Base64.getEncoder();

  private class SafeHeader {
    private final String key;
    private final byte[] raw;
    private String value;
    private final boolean _isBytes;

    public SafeHeader(HeaderValue hv) {
      this.key = hv.getKey();
      this.value = hv.getValue();
      this.raw = hv.getRawValue().toByteArray();
      if (this.value.isEmpty() && this.raw.length > 0) {
        this._isBytes = true;
        try {
          // envoy may pass headers with raw values that are simply UTF-8 bytes
          // or the value may actually include non-UTF-8 bytes. If it is UTF-8,
          // we can safely convert it to a string, otherwise we'll just leave it
          // in the raw field
          this.value = new String(this.raw, StandardCharsets.UTF_8);
        } catch (Exception e) {
          // if we can't convert it to a string explicitly, we'll cast into base64
          // so we can always treat header values as strings
          this.value = encoder.encodeToString(this.raw);
        }
      } else {
        this._isBytes = false;
      }
    }

    public String getKey() {
      return key;
    }

    public String getValue() {
      return value;
    }

    public byte[] getRaw() {
      return raw;
    }

    public boolean isBytes() {
      return _isBytes;
    }
  }

  private final String requestIdHeaderName;

  protected long[] phaseDurations = {0L, 0L, 0L, 0L, 0L, 0L};

  protected Instant started;
  protected Duration duration;
  protected String scheme;
  protected String authority;
  protected String method;
  protected String path;
  protected String queryString;
  protected Map<String, List<String>> params;
  protected int status;
  protected String requestId;
  protected String processorId;
  protected final Map<String, Object> customData = new HashMap<String, Object>();

  protected RequestCase phase;
  protected Map<String, String> requestHeaders;
  protected Map<String, String> responseHeaders;
  protected AllHeaders allRequestHeaders;
  protected AllHeaders allResponseHeaders;
  protected boolean endOfStream;
  protected List<HeaderValueOption> addHeaders;
  protected List<String> removeHeaders;

  /** Lower-cased header keys observed during a phase; drives mutation resolution. */
  protected Map<String, Boolean> phaseHeaderKeys;

  /** Lower-cased header keys observed during the request phases; carried into body phases. */
  protected Map<String, Boolean> reqHeaderKeys;

  /** Lower-cased header keys observed during the response phases; carried into body phases. */
  protected Map<String, Boolean> respHeaderKeys;

  protected boolean finished;
  protected boolean replace;
  protected boolean cancelled;
  protected boolean clearRouteCache;
  protected boolean observabilityMode;
  BodyMutation bodyMutation;
  ImmediateResponse immediateResponse;
  protected ProcessingMode modeOverride;
  protected ProtocolConfig protocolConfig;
  protected Map<String, Map<String, Object>> metadata;
  protected Map<String, Any> typedMetadata;
  protected Map<String, Object> attributes;
  protected Struct dynamicMetadata;
  protected Consumer<ProcessingResponse> sendTimeoutOverride;
  protected boolean timeoutExtended;

  public RequestContext() {
    this("x-request-id");
  }

  public RequestContext(String requestIdHeaderName) {
    this.requestIdHeaderName = requestIdHeaderName;
    initialize();
  }

  protected void initialize() {
    started = Instant.now();
    status = 0;
    finished = false;
    duration = Duration.ZERO;
    requestHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    responseHeaders = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
    allRequestHeaders = new AllHeaders();
    allResponseHeaders = new AllHeaders();
    protocolConfig = null;
    metadata = new LinkedHashMap<String, Map<String, Object>>();
    typedMetadata = new LinkedHashMap<String, Any>();
    attributes = new LinkedHashMap<String, Object>();
    reset();
  }

  protected void reset(RequestCase phase) {
    this.phase = phase;
    // we can't reset until we've seen the RequestHeaders phase with data
    if (phase != RequestCase.REQUEST_HEADERS) {
      reset();
    }
  }

  private void reset() {
    cancelled = false;
    replace = false;
    endOfStream = false;
    clearRouteCache = false;
    addHeaders = new ArrayList<HeaderValueOption>();
    removeHeaders = new ArrayList<String>();
    bodyMutation = null;
    // phase header state is re-seeded per phase by the server; the resolved
    // (post-mutation) request/response key sets persist across phase carries
    phaseHeaderKeys = null;
    observabilityMode = false;
    modeOverride = null;
    dynamicMetadata = null;
    timeoutExtended = false;
  }

  /** Merges inbound {@code ProcessingRequest.metadata_context} into stream-scoped metadata maps. */
  protected void mergeMetadataContext(Metadata md) {
    if (md == null) {
      return;
    }
    for (Map.Entry<String, Struct> entry : md.getFilterMetadataMap().entrySet()) {
      Struct st = entry.getValue();
      if (st == null) {
        continue;
      }
      Map<String, Object> nsMap = metadata.get(entry.getKey());
      if (nsMap == null) {
        nsMap = new LinkedHashMap<String, Object>();
        metadata.put(entry.getKey(), nsMap);
      }
      nsMap.putAll(StructValues.toMap(st));
    }
    for (Map.Entry<String, Any> entry : md.getTypedFilterMetadataMap().entrySet()) {
      Any anyVal = entry.getValue();
      if (anyVal != null) {
        typedMetadata.put(entry.getKey(), anyVal);
      }
    }
  }

  /** Merges inbound CEL {@code ProcessingRequest.attributes} flattened into {@code attributes}. */
  protected void mergeAttributes(Map<String, Struct> attrs) {
    if (attrs == null || attrs.isEmpty()) {
      return;
    }
    List<String> sortedNamespaces = new ArrayList<String>(attrs.keySet());
    Collections.sort(sortedNamespaces);
    for (String ns : sortedNamespaces) {
      Struct st = attrs.get(ns);
      if (st != null) {
        attributes.putAll(StructValues.toMap(st));
      }
    }
  }

  /** context initialization routine */
  protected Map<String, String> initializeRequest(HeaderMap protoHeaders) {
    // if (protoHeaders == null) { ... } // this is a problem, not initializable
    for (HeaderValue hv : protoHeaders.getHeadersList()) {
      final SafeHeader header = new SafeHeader(hv);
      final String key = header.getKey(); // not null
      final String val = header.getValue(); // not null
      if (key.startsWith(":")) {
        // these are standard envoy headers we expect to be well-formed
        switch (key) {
          case ":scheme":
            scheme = val;
            break;
          case ":authority":
            authority = val;
            break;
          case ":method":
            method = val;
            break;
          case ":path":
            parsePath(val);
            break;
          default:
            break;
        }
      } else {
        if (key.equalsIgnoreCase(requestIdHeaderName)) {
          requestId = val;
        }
        requestHeaders.put(key, val);
        allRequestHeaders.add(key, val);
      }
    }
    return requestHeaders;
  }

  /** parse a URL path */
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

  /** parse query parameters into a map */
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
    if (headers == null) {
      // only issue here is that we don't know the status... but should not
      // be null if correctly passed through from envoy
      return responseHeaders;
    }
    for (HeaderValue hv : headers.getHeadersList()) {
      final SafeHeader header = new SafeHeader(hv);
      final String key = header.getKey(); // not null
      final String val = header.getValue(); // not null
      if (key.startsWith(":")) {
        switch (key) {
          case ":status":
            try {
              status = Integer.parseInt(val);
            } catch (NumberFormatException e) {
              status = 0; // technically
            }
            break;
          default:
            break;
        }
      } else {
        responseHeaders.put(key, val);
        allResponseHeaders.add(key, val);
      }
    }
    return responseHeaders;
  }

  protected void updateDuration(RequestCase phase, Duration duration) {
    // technically, phase.getNumber()-2 would work, but this is more readable
    // and doesn't depend on the generated code for the derived Enum RequestCase
    final Long nanos = duration.toNanos();
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
    this.duration = this.duration.plus(duration);
  }

  public Map<String, String> getRequestHeaders() {
    return requestHeaders;
  }

  /** All request headers (multi-value, raw-value capable) for the current phase. */
  public AllHeaders getAllRequestHeaders() {
    return allRequestHeaders;
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

  /** All response headers (multi-value, raw-value capable) for the current phase. */
  public AllHeaders getAllResponseHeaders() {
    return allResponseHeaders;
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

  /** The processing phase currently being handled; defaults to "unspecified" before any phase. */
  public RequestCase getPhase() {
    if (phase == null) {
      // no phase has been processed yet; treat as the default/unspecified phase
      return RequestCase.REQUEST_NOT_SET;
    }
    return phase;
  }

  /**
   * Human-readable name of the current processing phase, e.g. "REQUEST_HEADERS"; "UNDETERMINED" if
   * unspecified.
   */
  public String getPhaseName() {
    if (phase == null) {
      return "UNDETERMINED";
    }
    switch (phase) {
      case REQUEST_HEADERS:
        return "REQUEST_HEADERS";
      case REQUEST_BODY:
        return "REQUEST_BODY";
      case REQUEST_TRAILERS:
        return "REQUEST_TRAILERS";
      case RESPONSE_HEADERS:
        return "RESPONSE_HEADERS";
      case RESPONSE_BODY:
        return "RESPONSE_BODY";
      case RESPONSE_TRAILERS:
        return "RESPONSE_TRAILERS";
      default:
        return "UNDETERMINED";
    }
  }

  /**
   * The numeric processing phase currently being handled; defaults to "unspecified" before any
   * phase.
   */
  public int getPhaseNumber() {
    if (phase == null) {
      // no phase has been processed yet; treat as the default/unspecified phase
      return 0;
    }
    switch (phase) {
      case REQUEST_HEADERS:
        return 1;
      case REQUEST_BODY:
        return 2;
      case REQUEST_TRAILERS:
        return 3;
      case RESPONSE_HEADERS:
        return 4;
      case RESPONSE_BODY:
        return 5;
      case RESPONSE_TRAILERS:
        return 6;
      default:
        // unspecified/unknown phases map to the default (unspecified) phase
        return 0;
    }
  }

  // TODO: don't return null, throw a special "RequestNotInitialized" exception?
  // We don't know the request ID until we've seen the headers, but also do we
  // require a request ID? From the POV of the context, we can just generate one
  // to use as a key for potential storage in the processor if that uses concurrency
  // and needs to store state per request.
  public String getRequestId() {
    return requestId;
  }

  public void setProcessingComplete() {
    finished = true;
  }

  public boolean isProcessingComplete() {
    return finished || cancelled;
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

  /** Whether the current processing phase runs with envoy's observability_mode enabled. */
  public boolean isObservabilityMode() {
    return observabilityMode;
  }

  /**
   * Returns Envoy's ext_proc protocol configuration captured from the first {@code
   * ProcessingRequest} of the stream, or {@code null} when absent.
   */
  public ProtocolConfig getProtocolConfig() {
    return protocolConfig;
  }

  /**
   * Returns untyped dynamic metadata forwarded by Envoy ({@code metadata_context.filter_metadata}),
   * keyed by namespace and merged across all phases of the stream.
   */
  public Map<String, Map<String, Object>> getMetadata() {
    return metadata;
  }

  /**
   * Returns typed dynamic metadata forwarded by Envoy ({@code
   * metadata_context.typed_filter_metadata}), keyed by namespace and merged across the stream.
   */
  public Map<String, Any> getTypedMetadata() {
    return typedMetadata;
  }

  /**
   * Returns evaluated CEL request/response attributes forwarded by Envoy, flattened by attribute
   * name and merged across all phases of the stream.
   */
  public Map<String, Object> getAttributes() {
    return attributes;
  }

  /** Returns the pending outbound {@code dynamic_metadata} Struct for the current phase. */
  public Struct getDynamicMetadata() {
    return dynamicMetadata;
  }

  public String getProcessorId() {
    return processorId;
  }

  public void setProcessorId(String processorId) {
    if (this.processorId == null) {
      this.processorId = processorId;
    }
  }

  /**
   * Returns the custom data value stored under the given name for this request, or null if no value
   * has been set for that name. Values set via {@link #setValue(String, Object)} persist across
   * processing phases within the same stream.
   */
  public Object getValue(String name) {
    return customData.get(name);
  }

  /**
   * Returns the custom data value stored under the given name for this request, cast to the
   * requested type, or null if no value has been set for that name.
   */
  public <T> T getValue(String name, Class<T> type) {
    return type.cast(customData.get(name));
  }

  /** Stores a custom data value under the given name for this request. */
  public void setValue(String name, Object value) {
    customData.put(name, value);
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

  /** NOTE: immediate response only accepts string bodies */
  public void cancelRequest(int status, Map<String, String> headers, String body) {
    cancelled = true;
    int effectiveStatus = status <= 0 ? 400 : status;
    StatusCode statusCode = StatusCode.forNumber(effectiveStatus);
    if (statusCode == null) {
      statusCode = StatusCode.InternalServerError;
    }
    ImmediateResponse.Builder builder =
        ImmediateResponse.newBuilder()
            .setStatus(HttpStatus.newBuilder().setCode(statusCode).build())
            .setBody(body == null ? ByteString.EMPTY : ByteString.copyFromUtf8(body));
    if (headers != null && !headers.isEmpty()) {
      HeaderMutation.Builder headerMutation = HeaderMutation.newBuilder();
      for (Map.Entry<String, String> entry : headers.entrySet()) {
        String key = entry.getKey();
        String value = entry.getValue();
        if (key == null || key.isEmpty() || value == null) {
          continue;
        }
        headerMutation.addSetHeaders(
            buildHeaderValueOption(
                key, value, HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD));
      }
      if (headerMutation.getSetHeadersCount() > 0) {
        builder.setHeaders(headerMutation.build());
      }
    }
    immediateResponse = builder.build();
  }

  /**
   * Requests a dynamic {@code ProcessingMode} override for this stream on header phase responses.
   * Only permitted during header phases ({@code REQUEST_HEADERS} or {@code RESPONSE_HEADERS}) and
   * applied to the response only when not cancelled.
   *
   * @throws IllegalStateException if called during a body or trailer phase
   * @throws IllegalArgumentException if {@code mode} or any of its fields is {@code null}
   */
  public void overrideMode(ModeOverride mode) {
    if (phase != null
        && phase != RequestCase.REQUEST_NOT_SET
        && phase != RequestCase.REQUEST_HEADERS
        && phase != RequestCase.RESPONSE_HEADERS) {
      throw new IllegalStateException(
          "mode_override is only supported during header phases, not " + phase);
    }
    if (mode == null) {
      throw new IllegalArgumentException("mode override must not be null");
    }
    ProcessingMode resolved = mode.toEnvoyProcessingMode();
    this.modeOverride = resolved;
  }

  /** Clears any pending dynamic mode override on the current phase. */
  public void clearModeOverride() {
    this.modeOverride = null;
  }

  /**
   * Convenience override that instructs Envoy to skip all remaining processing phases (response
   * headers, request/response bodies, and request/response trailers) for this stream.
   */
  public void skipRemainingPhases() {
    overrideMode(
        new ModeOverride()
            .setResponseHeaderMode(ModeOverride.HeaderSendMode.SKIP)
            .setRequestBodyMode(ModeOverride.BodySendMode.NONE)
            .setResponseBodyMode(ModeOverride.BodySendMode.NONE)
            .setRequestTrailerMode(ModeOverride.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
  }

  /**
   * Returns a {@link ModeOverride} pre-populated with Envoy's configured {@code requestBodyMode}
   * and {@code responseBodyMode} from {@link #getProtocolConfig()} (when present) and {@code
   * DEFAULT} for header and trailer modes.
   */
  public ModeOverride defaultModeOverride() {
    if (protocolConfig == null) {
      return new ModeOverride();
    }
    return new ModeOverride()
        .setResponseHeaderMode(ModeOverride.HeaderSendMode.DEFAULT)
        .setRequestBodyMode(protocolConfig.requestBodyMode)
        .setResponseBodyMode(protocolConfig.responseBodyMode)
        .setRequestTrailerMode(ModeOverride.HeaderSendMode.DEFAULT)
        .setResponseTrailerMode(ModeOverride.HeaderSendMode.DEFAULT);
  }

  /** Returns the pending Envoy {@link ProcessingMode} override for this phase, or {@code null}. */
  public ProcessingMode getModeOverride() {
    return modeOverride;
  }

  /**
   * Sets {@code key=value} under {@code namespace} in the current phase's {@code
   * ProcessingResponse.dynamic_metadata}.
   */
  public void setDynamicMetadata(String namespace, String key, Object value) {
    if (namespace == null || namespace.isEmpty()) {
      throw new IllegalArgumentException("dynamic metadata namespace cannot be empty");
    }
    if (key == null || key.isEmpty()) {
      throw new IllegalArgumentException("dynamic metadata key cannot be empty");
    }
    Value pbVal = StructValues.toProtoValue(value);
    mergeNamespaceFields(namespace, Collections.singletonMap(key, pbVal));
  }

  /**
   * Merges {@code values} under {@code namespace} in the current phase's {@code
   * ProcessingResponse.dynamic_metadata}. Validation is all-or-nothing.
   */
  public void setDynamicMetadataNamespace(String namespace, Map<String, ?> values) {
    if (namespace == null || namespace.isEmpty()) {
      throw new IllegalArgumentException("dynamic metadata namespace cannot be empty");
    }
    if (values == null) {
      throw new IllegalArgumentException("dynamic metadata values must not be null");
    }
    Map<String, Value> converted = new LinkedHashMap<String, Value>(values.size());
    for (Map.Entry<String, ?> entry : values.entrySet()) {
      String key = entry.getKey();
      if (key == null || key.isEmpty()) {
        throw new IllegalArgumentException("dynamic metadata key cannot be empty");
      }
      converted.put(key, StructValues.toProtoValue(entry.getValue()));
    }
    mergeNamespaceFields(namespace, converted);
  }

  /** Removes all pending outbound {@code dynamic_metadata} for the current phase. */
  public void clearDynamicMetadata() {
    this.dynamicMetadata = null;
  }

  private void mergeNamespaceFields(String namespace, Map<String, Value> fields) {
    Struct.Builder rootBuilder =
        dynamicMetadata == null ? Struct.newBuilder() : dynamicMetadata.toBuilder();
    Struct.Builder nsBuilder = Struct.newBuilder();
    Value existingVal = rootBuilder.getFieldsMap().get(namespace);
    if (existingVal != null && existingVal.hasStructValue()) {
      nsBuilder.mergeFrom(existingVal.getStructValue());
    }
    nsBuilder.putAllFields(fields);
    rootBuilder.putFields(namespace, Value.newBuilder().setStructValue(nsBuilder.build()).build());
    dynamicMetadata = rootBuilder.build();
  }

  /**
   * Builds an empty {@code CONTINUE} processing response for the given request phase; throws {@link
   * IllegalStateException} when {@code phase} is unset or unknown.
   */
  static ProcessingResponse emptyContinueResponse(RequestCase phase) {
    if (phase == null) {
      throw new IllegalStateException("unknown request phase: null");
    }
    CommonResponse common = CommonResponse.newBuilder().setStatus(ResponseStatus.CONTINUE).build();
    switch (phase) {
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
        throw new IllegalStateException("unknown request phase: " + phase);
    }
  }

  /**
   * Immediately sends an interim {@code ProcessingResponse} asking Envoy to replace the current
   * phase's {@code message_timeout} timer with {@code timeout} (requires {@code max_message_timeout
   * >= timeout >= 1ms} on Envoy's ext_proc filter). May be called at most once per phase and is a
   * no-op when {@link #isObservabilityMode()} is {@code true}.
   *
   * <p>Warning: ext_proc sits inline on Envoy's request/response hot path. Extending per-message
   * timeouts holds downstream connections, Envoy filter state, and any {@code BUFFERED} HTTP bodies
   * in memory, transmits an additional gRPC frame on the stream, and multiplies concurrent active
   * streams under load (Little's Law). Reserve this for slow-path operations and size {@link
   * ServerOptions}, Envoy circuit breakers, and route timeouts accordingly.
   */
  public void overrideMessageTimeout(Duration timeout) {
    if (timeout == null || timeout.compareTo(Duration.ofMillis(1)) < 0) {
      throw new IllegalArgumentException("override_message_timeout must be >= 1ms, got " + timeout);
    }
    if (phase == null || phase == RequestCase.REQUEST_NOT_SET) {
      throw new IllegalStateException("override_message_timeout requires an active request phase");
    }
    if (timeoutExtended) {
      throw new IllegalStateException("override_message_timeout already sent in current phase");
    }
    if (observabilityMode) {
      timeoutExtended = true;
      return;
    }
    if (sendTimeoutOverride == null) {
      throw new IllegalStateException("override_message_timeout requires an active gRPC stream");
    }
    com.google.protobuf.Duration protoTimeout =
        com.google.protobuf.Duration.newBuilder()
            .setSeconds(timeout.getSeconds())
            .setNanos(timeout.getNano())
            .build();
    ProcessingResponse interim =
        emptyContinueResponse(phase).toBuilder().setOverrideMessageTimeout(protoTimeout).build();
    sendTimeoutOverride.accept(interim);
    timeoutExtended = true;
  }

  /** return the response for a given phase; will not return null but may throw */
  public ProcessingResponse getResponse(RequestCase phase) {

    ProcessingResponse.Builder builder = ProcessingResponse.newBuilder();
    if (dynamicMetadata != null) {
      builder.setDynamicMetadata(dynamicMetadata);
    }

    if (cancelled) {
      return builder.setImmediateResponse(immediateResponse).build();
    }

    switch (phase) {
      case REQUEST_HEADERS:
        builder.setRequestHeaders(prepareHeadersResponse());
        if (modeOverride != null) {
          builder.setModeOverride(modeOverride);
        }
        return builder.build();
      case REQUEST_BODY:
        return builder.setRequestBody(prepareBodyResponse()).build();
      case REQUEST_TRAILERS:
        return builder.setRequestTrailers(prepareTrailersResponse()).build();
      case RESPONSE_HEADERS:
        builder.setResponseHeaders(prepareHeadersResponse());
        if (modeOverride != null) {
          builder.setModeOverride(modeOverride);
        }
        return builder.build();
      case RESPONSE_BODY:
        return builder.setResponseBody(prepareBodyResponse()).build();
      case RESPONSE_TRAILERS:
        return builder.setResponseTrailers(prepareTrailersResponse()).build();
      default:
        throw new RuntimeException("unknown request phase");
    }
  }

  /** Lower-cased header key set observed in a phase, for mutation resolution. */
  protected Map<String, Boolean> headerKeySet(AllHeaders headers) {
    Map<String, Boolean> keys = new HashMap<String, Boolean>();
    if (headers != null) {
      for (String key : headers.headers.keySet()) {
        if (key != null) {
          keys.put(key.toLowerCase(Locale.ROOT), Boolean.TRUE);
        }
      }
    }
    return keys;
  }

  /** Lower-cased header key set observed in a phase, for mutation resolution. */
  protected Map<String, Boolean> headerKeySet(Map<String, String> headers) {
    Map<String, Boolean> keys = new HashMap<String, Boolean>();
    if (headers != null) {
      for (String key : headers.keySet()) {
        if (key != null) {
          keys.put(key.toLowerCase(Locale.ROOT), Boolean.TRUE);
        }
      }
    }
    return keys;
  }

  /** Pending header mutations accumulated during the current phase. */
  private HeaderMutation pendingHeaderMutation() {
    HeaderMutation.Builder builder = HeaderMutation.newBuilder();
    if (!addHeaders.isEmpty()) {
      builder.addAllSetHeaders(addHeaders);
    }
    if (!removeHeaders.isEmpty()) {
      builder.addAllRemoveHeaders(removeHeaders);
    }
    return builder.build();
  }

  /**
   * Resolves pending header mutations against the known phase header keys, enforcing {@code
   * ADD_IF_ABSENT} and {@code OVERWRITE_IF_EXISTS} semantics while Envoy's ext_proc filter ignores
   * {@code HeaderValueOption.append_action} (envoyproxy/envoy#48886).
   *
   * <p>Returns the resolved mutation (the pending mutation unfiltered when no phase header state is
   * known yet) and records the post-mutation key set on the context for the next phase.
   */
  protected HeaderMutation resolvedHeaderMutation() {
    HeaderMutation pending = pendingHeaderMutation();
    if (phaseHeaderKeys == null) {
      // no phase header state yet; nothing to resolve against
      return pending;
    }
    Map<String, Boolean> state = new HashMap<String, Boolean>(phaseHeaderKeys);
    List<String> removeHeaders = new ArrayList<String>();
    if (pending.getRemoveHeadersCount() > 0) {
      removeHeaders.addAll(pending.getRemoveHeadersList());
    }
    for (String rm : removeHeaders) {
      // Envoy ignores attempts to remove pseudo-headers or the host header.
      if (rm == null) {
        continue;
      }
      String k = rm.toLowerCase(Locale.ROOT);
      if (!k.startsWith(":") && !k.equals("host")) {
        state.remove(k);
      }
    }
    List<HeaderValueOption> setHeaders = new ArrayList<HeaderValueOption>();
    if (pending.getSetHeadersCount() > 0) {
      for (HeaderValueOption ho : pending.getSetHeadersList()) {
        if (ho == null) {
          continue;
        }
        String k = ho.getHeader().getKey().toLowerCase(Locale.ROOT);
        boolean exists = state.get(k) != null && state.get(k);
        switch (ho.getAppendAction()) {
          case ADD_IF_ABSENT:
            if (exists) {
              continue;
            }
            state.put(k, Boolean.TRUE);
            setHeaders.add(ho);
            break;
          case OVERWRITE_IF_EXISTS:
            if (!exists) {
              continue;
            }
            setHeaders.add(ho);
            break;
          default:
            state.put(k, Boolean.TRUE);
            setHeaders.add(ho);
            break;
        }
      }
    }
    HeaderMutation.Builder resolved = HeaderMutation.newBuilder();
    resolved.addAllSetHeaders(setHeaders);
    resolved.addAllRemoveHeaders(pending.getRemoveHeadersList());
    // record the post-mutation key set for the next phase
    if (phase == RequestCase.REQUEST_HEADERS || phase == RequestCase.REQUEST_BODY) {
      reqHeaderKeys = state;
    } else if (phase == RequestCase.RESPONSE_HEADERS || phase == RequestCase.RESPONSE_BODY) {
      respHeaderKeys = state;
    }
    return resolved.build();
  }

  protected HeadersResponse prepareHeadersResponse() {
    return HeadersResponse.newBuilder().setResponse(prepareCommonResponse()).build();
  }

  protected BodyResponse prepareBodyResponse() {
    return BodyResponse.newBuilder().setResponse(prepareCommonResponse()).build();
  }

  private CommonResponse prepareCommonResponse() {
    CommonResponse.Builder common =
        CommonResponse.newBuilder()
            .setStatus(replace ? ResponseStatus.CONTINUE_AND_REPLACE : ResponseStatus.CONTINUE)
            .setClearRouteCache(clearRouteCache);
    HeaderMutation resolved = resolvedHeaderMutation();
    if (resolved.getSetHeadersCount() > 0 || resolved.getRemoveHeadersCount() > 0) {
      common.setHeaderMutation(resolved);
    }
    if (bodyMutation != null) {
      common.setBodyMutation(bodyMutation);
    }
    return common.build();
  }

  protected TrailersResponse prepareTrailersResponse() {
    TrailersResponse.Builder builder = TrailersResponse.newBuilder();
    HeaderMutation resolved = resolvedHeaderMutation();
    if (resolved.getSetHeadersCount() > 0 || resolved.getRemoveHeadersCount() > 0) {
      builder.setHeaderMutation(resolved);
    }
    return builder.build();
  }

  public void appendHeader(String name, String value) {
    this.updateHeader(name, value, HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
  }

  public void addHeader(String name, String value) {
    this.updateHeader(name, value, HeaderValueOption.HeaderAppendAction.ADD_IF_ABSENT);
  }

  public void overwriteHeader(String name, String value) {
    this.updateHeader(name, value, HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD);
  }

  public void removeHeader(String name) {
    if (name == null || name.isEmpty()) {
      return;
    }
    removeHeaders.add(name.toLowerCase(Locale.ROOT));
  }

  public void appendHeaders(Map<String, String> headers) {
    this.updateHeaders(headers, HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD);
  }

  public void addHeaders(Map<String, String> headers) {
    this.updateHeaders(headers, HeaderValueOption.HeaderAppendAction.ADD_IF_ABSENT);
  }

  public void overwriteHeaders(Map<String, String> headers) {
    this.updateHeaders(headers, HeaderValueOption.HeaderAppendAction.OVERWRITE_IF_EXISTS_OR_ADD);
  }

  public void removeHeaders(List<String> headers) {
    if (headers == null) {
      return;
    }
    for (String header : headers) {
      this.removeHeader(header);
    }
  }

  protected void updateHeader(
      String name, String value, HeaderValueOption.HeaderAppendAction action) {
    if (name == null || name.isEmpty() || value == null) {
      return;
    }
    addHeaders.add(buildHeaderValueOption(name, value, action));
  }

  private HeaderValueOption buildHeaderValueOption(
      String name, String value, HeaderValueOption.HeaderAppendAction action) {
    HeaderValueOption.Builder builder =
        HeaderValueOption.newBuilder()
            .setHeader(
                HeaderValue.newBuilder()
                    .setKey(name.toLowerCase(Locale.ROOT))
                    .setRawValue(ByteString.copyFromUtf8(value))
                    .build())
            .setAppendAction(action);
    // Envoy's ext_proc MutationUtils::applyHeaderMutations evaluates the deprecated
    // HeaderValueOption.append (defaulting to false) rather than append_action
    // (envoyproxy/envoy#48886). Setting Append=true for APPEND_IF_EXISTS_OR_ADD preserves
    // append behavior without colliding with a non-zero AppendAction.
    if (action == HeaderValueOption.HeaderAppendAction.APPEND_IF_EXISTS_OR_ADD) {
      builder.setAppend(BoolValue.of(true));
    }
    return builder.build();
  }

  protected void updateHeaders(
      Map<String, String> headers, HeaderValueOption.HeaderAppendAction action) {
    if (headers == null) {
      return;
    }
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

  /**
   * Replaces the whole body (BUFFERED semantics: one message is the whole body) and updates the
   * content-length to match. A {@code null} body is a no-op; an empty body delegates to {@link
   * #clearBody()}. Under STREAMED body mode, use {@link #replaceBodyChunk(byte[])} instead, which
   * is chunk semantics.
   */
  public void replaceBody(byte[] body) {
    if (body == null) {
      return;
    }
    if (body.length == 0) {
      clearBody();
      return;
    }
    bodyMutation = BodyMutation.newBuilder().setBody(ByteString.copyFrom(body)).build();
    overwriteHeader("content-length", String.valueOf(body.length));
  }

  /**
   * Replaces the whole body (BUFFERED semantics: one message is the whole body) and updates the
   * content-length to match. A {@code null} body is a no-op; an empty body delegates to {@link
   * #clearBody()}. Under STREAMED body mode, use {@link #replaceBodyChunk(String)} instead, which
   * is chunk semantics.
   */
  public void replaceBody(String body) {
    if (body == null) {
      return;
    }
    replaceBody(body.getBytes(StandardCharsets.UTF_8));
  }

  /**
   * Clears the whole body and zeroes the content-length (BUFFERED semantics). Under STREAMED body
   * mode, use {@link #clearBodyChunk()} instead, which clears a single chunk.
   */
  public void clearBody() {
    bodyMutation = BodyMutation.newBuilder().setClearBody(true).build();
    overwriteHeader("content-length", "0");
  }

  /** Signals that the route cache should be cleared before the next upstream request. */
  public void clearRouteCache() {
    this.clearRouteCache = true;
  }

  /** Clears the route-cache-clear signal. */
  public void unClearRouteCache() {
    this.clearRouteCache = false;
  }

  /** Whether a clear-route-cache signal is pending for the current phase. */
  public boolean isClearRouteCache() {
    return clearRouteCache;
  }
}
