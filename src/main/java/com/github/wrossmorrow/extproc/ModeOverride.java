package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;

/**
 * Per-request processing mode overrides applied via {@code ProcessingResponse.mode_override} during
 * request or response header processing when {@code allow_mode_override} is enabled on Envoy's
 * ext_proc filter.
 */
public class ModeOverride {

  /** Controls whether headers or trailers are sent to the external processor. */
  public enum HeaderSendMode {
    DEFAULT,
    SEND,
    SKIP
  }

  /** Controls how request or response bodies are sent to the external processor. */
  public enum BodySendMode {
    NONE,
    STREAMED,
    BUFFERED,
    BUFFERED_PARTIAL,
    FULL_DUPLEX_STREAMED,
    GRPC
  }

  public HeaderSendMode responseHeaderMode = HeaderSendMode.DEFAULT;
  public BodySendMode requestBodyMode = BodySendMode.NONE;
  public BodySendMode responseBodyMode = BodySendMode.NONE;
  public HeaderSendMode requestTrailerMode = HeaderSendMode.DEFAULT;
  public HeaderSendMode responseTrailerMode = HeaderSendMode.DEFAULT;

  /** Creates a new {@link ModeOverride} with Envoy defaults ({@code DEFAULT} / {@code NONE}). */
  public ModeOverride() {}

  public ModeOverride setResponseHeaderMode(HeaderSendMode responseHeaderMode) {
    this.responseHeaderMode = responseHeaderMode;
    return this;
  }

  public ModeOverride setRequestBodyMode(BodySendMode requestBodyMode) {
    this.requestBodyMode = requestBodyMode;
    return this;
  }

  public ModeOverride setResponseBodyMode(BodySendMode responseBodyMode) {
    this.responseBodyMode = responseBodyMode;
    return this;
  }

  public ModeOverride setRequestTrailerMode(HeaderSendMode requestTrailerMode) {
    this.requestTrailerMode = requestTrailerMode;
    return this;
  }

  public ModeOverride setResponseTrailerMode(HeaderSendMode responseTrailerMode) {
    this.responseTrailerMode = responseTrailerMode;
    return this;
  }

  /** Converts this override configuration into Envoy's {@link ProcessingMode} protobuf message. */
  public ProcessingMode toEnvoyProcessingMode() {
    if (responseHeaderMode == null
        || requestBodyMode == null
        || responseBodyMode == null
        || requestTrailerMode == null
        || responseTrailerMode == null) {
      throw new IllegalArgumentException("mode override fields must not be null");
    }
    return ProcessingMode.newBuilder()
        .setResponseHeaderMode(toHeaderSendMode(responseHeaderMode))
        .setRequestBodyMode(toBodySendMode(requestBodyMode))
        .setResponseBodyMode(toBodySendMode(responseBodyMode))
        .setRequestTrailerMode(toHeaderSendMode(requestTrailerMode))
        .setResponseTrailerMode(toHeaderSendMode(responseTrailerMode))
        .build();
  }

  private static ProcessingMode.HeaderSendMode toHeaderSendMode(HeaderSendMode mode) {
    switch (mode) {
      case DEFAULT:
        return ProcessingMode.HeaderSendMode.DEFAULT;
      case SEND:
        return ProcessingMode.HeaderSendMode.SEND;
      case SKIP:
        return ProcessingMode.HeaderSendMode.SKIP;
      default:
        throw new IllegalArgumentException("unsupported HeaderSendMode: " + mode);
    }
  }

  private static ProcessingMode.BodySendMode toBodySendMode(BodySendMode mode) {
    switch (mode) {
      case NONE:
        return ProcessingMode.BodySendMode.NONE;
      case STREAMED:
        return ProcessingMode.BodySendMode.STREAMED;
      case BUFFERED:
        return ProcessingMode.BodySendMode.BUFFERED;
      case BUFFERED_PARTIAL:
        return ProcessingMode.BodySendMode.BUFFERED_PARTIAL;
      case FULL_DUPLEX_STREAMED:
        return ProcessingMode.BodySendMode.FULL_DUPLEX_STREAMED;
      case GRPC:
        return ProcessingMode.BodySendMode.GRPC;
      default:
        throw new IllegalArgumentException("unsupported BodySendMode: " + mode);
    }
  }
}
