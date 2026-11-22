package com.github.wrossmorrow.extproc.processors;

import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest.RequestCase;
import com.github.wrossmorrow.extproc.ProcessingOptions;
import com.github.wrossmorrow.extproc.RequestContext;
import com.github.wrossmorrow.extproc.RequestProcessor;
import com.github.wrossmorrow.extproc.RequestProcessorHealthManager;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

public class DedupRequestProcessor implements RequestProcessor {

  protected final ConcurrentMap<String, String> inflight = new ConcurrentHashMap<String, String>();

  public DedupRequestProcessor() {}

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

  @Override
  public void errorHandler(RequestContext ctx, RequestCase phase, Throwable err) {
    releaseInflight(ctx);
  }

  public void processRequestHeaders(RequestContext ctx, Map<String, String> headers) {
    if (!isDedupable(ctx)) {
      return;
    }
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      String method = ctx.getMethod() == null ? "" : ctx.getMethod();
      String path = ctx.getPath() == null ? "" : ctx.getPath();
      checkAndRecordInflight(ctx, DigestRequestProcessor.sha256Hex(method + ":" + path));
    }
  }

  public void processRequestBody(RequestContext ctx, String body) {
    if (!isDedupable(ctx)) {
      return;
    }
    StringBuilder bodyBuf = ctx.getValue("dedupBody", StringBuilder.class);
    if (bodyBuf == null) {
      bodyBuf = new StringBuilder();
      ctx.setValue("dedupBody", bodyBuf);
    }
    if (body != null) {
      bodyBuf.append(body);
    }
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      String method = ctx.getMethod() == null ? "" : ctx.getMethod();
      String path = ctx.getPath() == null ? "" : ctx.getPath();
      checkAndRecordInflight(
          ctx, DigestRequestProcessor.sha256Hex(method + ":" + path + ":" + bodyBuf.toString()));
    }
  }

  public void processRequestTrailers(RequestContext ctx, Map<String, String> trailers) {}

  public void processResponseHeaders(RequestContext ctx, Map<String, String> headers) {
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      releaseInflight(ctx);
    }
  }

  public void processResponseBody(RequestContext ctx, String body) {
    if (Boolean.TRUE.equals(ctx.streamComplete())) {
      releaseInflight(ctx);
    }
  }

  public void processResponseTrailers(RequestContext ctx, Map<String, String> trailers) {
    releaseInflight(ctx);
  }

  private void checkAndRecordInflight(RequestContext ctx, String digest) {
    ctx.addHeader("x-extproc-request-digest", digest);
    String ownerId = ctx.getRequestId();
    if (ownerId == null || ownerId.isEmpty()) {
      ownerId = UUID.randomUUID().toString();
    }
    String existing = inflight.putIfAbsent(digest, ownerId);
    if (existing != null) {
      ctx.cancelRequest(
          409,
          "{\"message\":\"Duplicate request already in flight\",\"requestId\":\""
              + existing
              + "\"}");
      return;
    }
    ctx.setValue("digest", digest);
    ctx.setValue("dedupOwnerId", ownerId);
  }

  private void releaseInflight(RequestContext ctx) {
    String digest = ctx.getValue("digest", String.class);
    String ownerId = ctx.getValue("dedupOwnerId", String.class);
    if (digest != null && ownerId != null) {
      inflight.remove(digest, ownerId);
    }
    ctx.setValue("digest", null);
    ctx.setValue("dedupOwnerId", null);
  }

  private static boolean isDedupable(RequestContext ctx) {
    String method = ctx.getMethod();
    return "PUT".equals(method) || "POST".equals(method) || "PATCH".equals(method);
  }
}
