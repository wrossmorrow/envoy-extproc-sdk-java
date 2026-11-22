package com.github.wrossmorrow.extproc;

import build.buf.gen.envoy.service.ext_proc.v3.ProtocolConfiguration;
import java.util.Objects;

/**
 * Describes Envoy's ext_proc filter protocol configuration for the current HTTP stream, captured
 * from {@code ProcessingRequest.protocol_config} on the first message of the stream and retained
 * across all phases.
 */
public final class ProtocolConfig {

  public final ModeOverride.BodySendMode requestBodyMode;
  public final ModeOverride.BodySendMode responseBodyMode;
  public final boolean sendBodyWithoutWaitingForHeaderResponse;

  /** Creates a new {@link ProtocolConfig}, normalizing {@code null} body modes to {@code NONE}. */
  public ProtocolConfig(
      ModeOverride.BodySendMode requestBodyMode,
      ModeOverride.BodySendMode responseBodyMode,
      boolean sendBodyWithoutWaitingForHeaderResponse) {
    this.requestBodyMode =
        requestBodyMode == null ? ModeOverride.BodySendMode.NONE : requestBodyMode;
    this.responseBodyMode =
        responseBodyMode == null ? ModeOverride.BodySendMode.NONE : responseBodyMode;
    this.sendBodyWithoutWaitingForHeaderResponse = sendBodyWithoutWaitingForHeaderResponse;
  }

  /** Converts a protobuf {@link ProtocolConfiguration} message, returning {@code null} if null. */
  public static ProtocolConfig fromProto(ProtocolConfiguration proto) {
    if (proto == null) {
      return null;
    }
    return new ProtocolConfig(
        ModeOverride.BodySendMode.fromEnvoy(proto.getRequestBodyMode()),
        ModeOverride.BodySendMode.fromEnvoy(proto.getResponseBodyMode()),
        proto.getSendBodyWithoutWaitingForHeaderResponse());
  }

  public ModeOverride.BodySendMode getRequestBodyMode() {
    return requestBodyMode;
  }

  public ModeOverride.BodySendMode getResponseBodyMode() {
    return responseBodyMode;
  }

  public boolean isSendBodyWithoutWaitingForHeaderResponse() {
    return sendBodyWithoutWaitingForHeaderResponse;
  }

  @Override
  public boolean equals(Object o) {
    if (this == o) {
      return true;
    }
    if (!(o instanceof ProtocolConfig)) {
      return false;
    }
    ProtocolConfig that = (ProtocolConfig) o;
    return sendBodyWithoutWaitingForHeaderResponse == that.sendBodyWithoutWaitingForHeaderResponse
        && requestBodyMode == that.requestBodyMode
        && responseBodyMode == that.responseBodyMode;
  }

  @Override
  public int hashCode() {
    return Objects.hash(requestBodyMode, responseBodyMode, sendBodyWithoutWaitingForHeaderResponse);
  }

  @Override
  public String toString() {
    return "ProtocolConfig{"
        + "requestBodyMode="
        + requestBodyMode
        + ", responseBodyMode="
        + responseBodyMode
        + ", sendBodyWithoutWaitingForHeaderResponse="
        + sendBodyWithoutWaitingForHeaderResponse
        + '}';
  }
}
