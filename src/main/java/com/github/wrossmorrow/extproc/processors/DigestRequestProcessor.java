package com.github.wrossmorrow.extproc.processors;

import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;

public class DigestRequestProcessor implements RequestProcessor {

  private static final char[] HEX_DIGITS = "0123456789abcdef".toCharArray();

  public String getName() {
    return "digest";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {
    System.out.println(this.getClass().getCanonicalName() + " shutting down");
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    String method = ctx.getMethod() == null ? "" : ctx.getMethod();
    String path = ctx.getPath() == null ? "" : ctx.getPath();
    StringBuilder hashtext = new StringBuilder(method).append(':').append(path);
    ctx.setValue("hashtext", hashtext);
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      String digest = SHAHash(hashtext);
      ctx.addHeader("x-extproc-request-digest", digest);
      ctx.setValue("digest", digest);
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    StringBuilder hashtext = ctx.getValue("hashtext", StringBuilder.class);
    if (hashtext == null) {
      String method = ctx.getMethod() == null ? "" : ctx.getMethod();
      String path = ctx.getPath() == null ? "" : ctx.getPath();
      hashtext = new StringBuilder(method).append(':').append(path);
      ctx.setValue("hashtext", hashtext);
    }
    if (!Boolean.TRUE.equals(ctx.getValue("bodyStarted", Boolean.class))) {
      hashtext.append(':');
      ctx.setValue("bodyStarted", Boolean.TRUE);
    }
    if (body != null) {
      hashtext.append(body);
    }
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      String digest = SHAHash(hashtext);
      ctx.addHeader("x-extproc-request-digest", digest);
      ctx.setValue("digest", digest);
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      emitResponseDigest(ctx);
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      emitResponseDigest(ctx);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    emitResponseDigest(ctx);
  }

  private void emitResponseDigest(RequestContext ctx) {
    String digest = ctx.getValue("digest", String.class);
    if (digest == null) {
      StringBuilder hashtext = ctx.getValue("hashtext", StringBuilder.class);
      digest = hashtext == null ? "" : SHAHash(hashtext);
      ctx.setValue("digest", digest);
    }
    ctx.addHeader("x-extproc-request-digest", digest);
  }

  protected String SHAHash(StringBuilder input) {
    return sha256Hex(input == null ? "" : input.toString());
  }

  protected String convertToHex(final byte[] messageDigest) {
    return toHex(messageDigest);
  }

  static String sha256Hex(String input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest((input == null ? "" : input).getBytes(StandardCharsets.UTF_8));
      return toHex(d);
    } catch (NoSuchAlgorithmException e) {
      System.out.println("No such algorithm exception");
      return "";
    }
  }

  static String toHex(final byte[] messageDigest) {
    if (messageDigest == null) {
      return "";
    }
    char[] chars = new char[messageDigest.length * 2];
    for (int i = 0; i < messageDigest.length; i++) {
      int v = messageDigest[i] & 0xFF;
      chars[i * 2] = HEX_DIGITS[v >>> 4];
      chars[i * 2 + 1] = HEX_DIGITS[v & 0x0F];
    }
    return new String(chars);
  }
}
