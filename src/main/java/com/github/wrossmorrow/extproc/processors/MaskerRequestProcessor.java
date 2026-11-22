package com.github.wrossmorrow.extproc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.wrossmorrow.extproc.AllHeaders;
import com.github.wrossmorrow.extproc.ModeOverride;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
import java.util.Locale;
import java.util.Map;

public class MaskerRequestProcessor implements RequestProcessor {

  private static final ObjectMapper MAPPER = new ObjectMapper();

  /**
   * Dot-delimited JSON field paths to mask for the request phase. Empty means "do not mask". The
   * upstream example masks "maskme" and "mask.me".
   */
  protected final List<String> maskedRequestPaths = Arrays.asList("maskme", "mask.me");

  /**
   * Dot-delimited JSON field paths to mask for the response phase. Empty means "do not mask". The
   * upstream example has no response masking configured.
   */
  protected final List<String> maskedResponsePaths = Arrays.asList();

  private final ProcessingOptions options = new ProcessingOptions();

  public String getName() {
    return "masker";
  }

  public ProcessingOptions getOptions() {
    return options;
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    boolean needRequestBody =
        !Boolean.TRUE.equals(ctx.streamComplete())
            && !maskedRequestPaths.isEmpty()
            && isRequestMaybeJson(ctx);
    boolean needResponseHeaders = wantsResponseHeaders();
    if (!needRequestBody && !needResponseHeaders) {
      ctx.skipRemainingPhases();
      return;
    }
    ctx.overrideMode(
        new ModeOverride()
            .setResponseHeaderMode(
                needResponseHeaders
                    ? ModeOverride.HeaderSendMode.SEND
                    : ModeOverride.HeaderSendMode.SKIP)
            .setRequestBodyMode(
                needRequestBody
                    ? ModeOverride.BodySendMode.BUFFERED
                    : ModeOverride.BodySendMode.NONE)
            .setResponseBodyMode(ModeOverride.BodySendMode.NONE)
            .setRequestTrailerMode(ModeOverride.HeaderSendMode.SKIP)
            .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (maskedRequestPaths.isEmpty()) {
      return;
    }
    if (!isRequestMaybeJson(ctx)) {
      return;
    }
    final byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    final byte[] masked = maskJson(maskedRequestPaths, bodyBytes);
    if (masked != null) {
      ctx.replaceBody(masked);
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {
    // nothing to mask at the trailers phase
  }

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (!Boolean.TRUE.equals(ctx.streamComplete())
        && !maskedResponsePaths.isEmpty()
        && isResponseMaybeJson(ctx)) {
      ctx.overrideMode(
          new ModeOverride()
              .setResponseBodyMode(ModeOverride.BodySendMode.BUFFERED)
              .setResponseTrailerMode(ModeOverride.HeaderSendMode.SKIP));
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (maskedResponsePaths.isEmpty()) {
      return;
    }
    if (!isResponseMaybeJson(ctx)) {
      return;
    }
    final byte[] bodyBytes = body == null ? new byte[0] : body.getBytes(StandardCharsets.UTF_8);
    final byte[] masked = maskJson(maskedResponsePaths, bodyBytes);
    if (masked != null) {
      ctx.replaceBody(masked);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    // nothing to mask at the trailers phase
  }

  private boolean wantsResponseHeaders() {
    return !maskedResponsePaths.isEmpty()
        || Boolean.TRUE.equals(options.updateExtProcHeader)
        || Boolean.TRUE.equals(options.updateDurationHeader)
        || Boolean.TRUE.equals(options.downstreamDurationHeader);
  }

  /** Whether the request headers suggest the body may be JSON. */
  protected boolean isRequestMaybeJson(RequestContext ctx) {
    return hasJsonContentType(ctx.getAllRequestHeaders(), ctx.getRequestHeader("content-type"));
  }

  /** Whether the response headers suggest the body may be JSON. */
  protected boolean isResponseMaybeJson(RequestContext ctx) {
    return hasJsonContentType(ctx.getAllResponseHeaders(), ctx.getResponseHeader("content-type"));
  }

  /** Whether the request headers suggest the body may be JSON. */
  protected boolean isMaybeJson(RequestContext ctx) {
    return isRequestMaybeJson(ctx);
  }

  private static boolean hasJsonContentType(AllHeaders allHeaders, String fallbackValue) {
    if (isJsonContentType(fallbackValue)) {
      return true;
    }
    if (allHeaders != null) {
      for (final String contentType : allHeaders.values("content-type")) {
        if (isJsonContentType(contentType)) {
          return true;
        }
      }
      for (final String contentType : allHeaders.values("Content-Type")) {
        if (isJsonContentType(contentType)) {
          return true;
        }
      }
    }
    return false;
  }

  private static boolean isJsonContentType(String contentType) {
    return contentType != null
        && contentType.trim().toLowerCase(Locale.ROOT).startsWith("application/json");
  }

  /**
   * Replaces values at existing dot-delimited JSON paths with a masking value, returning {@code
   * null} if the body is not a JSON object or no configured paths matched.
   */
  protected byte[] maskJson(final List<String> jsonPaths, final byte[] body) {
    if (jsonPaths == null || jsonPaths.isEmpty() || body == null || body.length == 0) {
      return null;
    }
    try {
      final JsonNode root = MAPPER.readTree(body);
      if (root == null || !root.isObject()) {
        return null;
      }
      final ObjectNode object = (ObjectNode) root;
      boolean modified = false;
      for (final String jsonPath : jsonPaths) {
        if (jsonPath == null || jsonPath.isEmpty()) {
          continue;
        }
        final String[] parts = jsonPath.split("\\.", -1);
        ObjectNode target = object;
        for (int i = 0; i < parts.length - 1; i++) {
          final JsonNode child = target.get(parts[i]);
          if (child == null || !child.isObject()) {
            target = null;
            break;
          }
          target = (ObjectNode) child;
        }
        if (target != null) {
          final String leaf = parts[parts.length - 1];
          if (target.has(leaf)) {
            target.set(leaf, new TextNode("****"));
            modified = true;
          }
        }
      }
      return modified ? MAPPER.writeValueAsBytes(object) : null;
    } catch (final Exception e) {
      // leave the body unmasked if it is not parseable JSON
      return null;
    }
  }
}
