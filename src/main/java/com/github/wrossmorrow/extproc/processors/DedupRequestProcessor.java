package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HashMap;
import java.util.Map;

public class DedupRequestProcessor implements RequestProcessor {

  Map<String, String> inflight;

  public DedupRequestProcessor() {
    inflight = new HashMap<String, String>();
  }

  public String getName() {
    return "dedup";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    if (!isDedupable(ctx)) {
      return;
    }
    if (ctx.streamComplete()) {
      String digest = sha256Hex(ctx.getMethod() + ":" + ctx.getPath());
      ctx.setValue("digest", digest);
      ctx.addHeader("x-extproc-request-digest", digest);
      if (inflight.containsKey(digest)) {
        ctx.cancelRequest(
            409,
            "{\"message\":\"Duplicate request already in flight\",\"requestId\":\""
                + inflight.get(digest)
                + "\"}");
        return;
      }
      inflight.put(digest, ctx.getRequestId());
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (!isDedupable(ctx)) {
      return;
    }
    if (ctx.streamComplete()) {
      String digest = sha256Hex(ctx.getMethod() + ":" + ctx.getPath() + ":" + body);
      ctx.setValue("digest", digest);
      ctx.addHeader("x-extproc-request-digest", digest);
      if (inflight.containsKey(digest)) {
        ctx.cancelRequest(
            409,
            "{\"message\":\"Duplicate request already in flight\",\"requestId\":\""
                + inflight.get(digest)
                + "\"}");
        return;
      }
      inflight.put(digest, ctx.getRequestId());
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.streamComplete()) {
      String digest = (String) ctx.getValue("digest");
      if (digest != null) {
        inflight.remove(digest);
      }
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (ctx.streamComplete()) {
      String digest = (String) ctx.getValue("digest");
      if (digest != null) {
        inflight.remove(digest);
      }
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

  private static boolean isDedupable(RequestContext ctx) {
    String method = ctx.getMethod();
    return "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method);
  }

  private static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(input.getBytes(StandardCharsets.UTF_8));
      return hexEncode(d);
    } catch (NoSuchAlgorithmException e) {
      return "";
    }
  }

  private static String hexEncode(final byte[] messageDigest) {
    BigInteger bigint = new BigInteger(1, messageDigest);
    String hexText = bigint.toString(16);
    while (hexText.length() < 32) {
      hexText = "0".concat(hexText);
    }
    return hexText;
  }
}
