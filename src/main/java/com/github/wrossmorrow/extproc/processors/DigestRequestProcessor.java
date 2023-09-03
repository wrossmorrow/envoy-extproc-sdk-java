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

public class DigestRequestProcessor implements RequestProcessor {

  Map<String, StringBuilder> hashtexts;
  Map<String, String> digests;

  public DigestRequestProcessor() {
    hashtexts = new HashMap<String, StringBuilder>();
    digests = new HashMap<String, String>();
  }

  public String getName() {
    return "digest";
  }

  public ProcessingOptions getOptions() {
    return new ProcessingOptions();
  }

  public void setHealthManager(RequestProcessorHealthManager health) {}

  public void shutdown() {}

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    StringBuilder hashtext = new StringBuilder(ctx.getMethod() + ":" + ctx.getPath() + ":");
    if (ctx.streamComplete()) {
      String digest = SHAHash(hashtext);
      ctx.addHeader("x-extproc-request-digest", digest);
      digests.put(ctx.getRequestId(), digest);
    }
    hashtexts.put(ctx.getRequestId(), hashtext);
  }

  public void processRequestBody(RequestContext ctx, String body) {
    StringBuilder hashtext = hashtexts.get(ctx.getRequestId());
    hashtext.append(body);
    if (ctx.streamComplete()) {
      String digest = SHAHash(hashtext);
      ctx.addHeader("x-extproc-request-digest", digest);
      digests.put(ctx.getRequestId(), digest);
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (ctx.streamComplete()) {
      String digest = digests.getOrDefault(ctx.getRequestId(), "");
      ctx.addHeader("x-extproc-request-digest", digest);
      ctx.addHeader("x-extproc-response-digest", digest);
      hashtexts.remove(ctx.getRequestId());
      digests.remove(ctx.getRequestId());
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    StringBuilder hashtext = hashtexts.get(ctx.getRequestId());
    hashtext.append(body);
    if (ctx.streamComplete()) {
      String digest = digests.get(ctx.getRequestId());
      ctx.addHeader("x-extproc-request-digest", digest);
      ctx.addHeader("x-extproc-response-digest", SHAHash(hashtext));
      hashtexts.remove(ctx.getRequestId());
      digests.remove(ctx.getRequestId());
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {}

  protected String SHAHash(StringBuilder input) {
    try {
      MessageDigest md = MessageDigest.getInstance("SHA-256");
      byte[] d = md.digest(input.toString().getBytes(StandardCharsets.UTF_8));
      return convertToHex(d);
    } catch (NoSuchAlgorithmException e) {
      System.out.println("No such algorithm exception");
      return "";
    }
  }

  protected String convertToHex(final byte[] messageDigest) {
    BigInteger bigint = new BigInteger(1, messageDigest);
    String hexText = bigint.toString(16);
    while (hexText.length() < 32) {
      hexText = "0".concat(hexText);
    }
    return hexText;
  }
}
