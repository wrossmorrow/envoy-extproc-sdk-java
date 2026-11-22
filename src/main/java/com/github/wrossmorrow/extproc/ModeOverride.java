package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.extensions.filters.http.ext_proc.v3.ProcessingMode;
import java.util.Objects;

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
    GRPC;

    /** Maps an Envoy protobuf {@link ProcessingMode.BodySendMode} to {@link BodySendMode}. */
    public static BodySendMode fromEnvoy(ProcessingMode.BodySendMode mode) {
      if (mode == null) {
        return NONE;
      }
      switch (mode) {
        case STREAMED:
          return STREAMED;
        case BUFFERED:
          return BUFFERED;
        case BUFFERED_PARTIAL:
          return BUFFERED_PARTIAL;
        case FULL_DUPLEX_STREAMED:
          return FULL_DUPLEX_STREAMED;
        case GRPC:
          return GRPC;
        case NONE:
        default:
          return NONE;
      }
    }
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

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ModeOverride)) {
      return false;
    }
    ModeOverride that = (ModeOverride) o;
    return responseHeaderMode == that.responseHeaderMode
        && requestBodyMode == that.requestBodyMode
        && responseBodyMode == that.responseBodyMode
        && requestTrailerMode == that.requestTrailerMode
        && responseTrailerMode == that.responseTrailerMode;
  }

  @Override
  public int hashCode() {
    return Objects.hash(
        responseHeaderMode,
        requestBodyMode,
        responseBodyMode,
        requestTrailerMode,
        responseTrailerMode);
  }

  @Override
  public String toString() {
    return "ModeOverride{"
        + "responseHeaderMode="
        + responseHeaderMode
        + ", requestBodyMode="
        + requestBodyMode
        + ", responseBodyMode="
        + responseBodyMode
        + ", requestTrailerMode="
        + requestTrailerMode
        + ", responseTrailerMode="
        + responseTrailerMode
        + '}';
  }
}
