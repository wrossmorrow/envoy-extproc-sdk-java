package com.github.wrossmorrow.extproc.processors;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.nio.charset.StandardCharsets;
import java.util.Arrays;
import java.util.List;
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

  public String getName() {
    return "masker";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    // nothing to mask at the header phase
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (maskedRequestPaths.isEmpty()) {
      return;
    }
    if (!isMaybeJson(ctx)) {
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
    // response masking is not configured in the upstream example
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (maskedResponsePaths.isEmpty()) {
      return;
    }
    if (!isMaybeJson(ctx)) {
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

  /** Whether the phase's headers suggest the body may be JSON. */
  protected boolean isMaybeJson(RequestContext ctx) {
    final List<String> contentTypes = ctx.getAllRequestHeaders().values("content-type");
    for (final String contentType : contentTypes) {
      if (contentType != null && contentType.trim().toLowerCase().startsWith("application/json")) {
        return true;
      }
    }
    return false;
  }

  /** Replaces values at the given dot-delimited JSON paths with a masking value. */
  protected byte[] maskJson(final List<String> jsonPaths, final byte[] body) {
    try {
      final JsonNode root = MAPPER.readTree(body);
      final ObjectNode object = root.isObject() ? (ObjectNode) root : MAPPER.createObjectNode();
      for (final String jsonPath : jsonPaths) {
        final String[] parts = jsonPath.split("\\.");
        ObjectNode target = object;
        for (int i = 0; i < parts.length - 1; i++) {
          JsonNode child = target.get(parts[i]);
          if (child == null || !child.isObject()) {
            final ObjectNode created = MAPPER.createObjectNode();
            target.set(parts[i], created);
            target = created;
          } else {
            target = (ObjectNode) child;
          }
        }
        target.set(parts[parts.length - 1], new TextNode("****"));
      }
      return MAPPER.writeValueAsBytes(object);
    } catch (final Exception e) {
      // leave the body unmasked if it is not parseable JSON
      return body;
    }
  }
}
