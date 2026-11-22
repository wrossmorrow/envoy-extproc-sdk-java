package com.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import build.buf.gen.envoy.config.core.v3.HeaderMap;
import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.config.core.v3.HeaderValueOption;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.HttpTrailers;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import build.buf.gen.envoy.type.v3.StatusCode;
import com.github.wrossmorrow.extproc.ExternalProcessor;
import com.github.wrossmorrow.extproc.processors.DedupRequestProcessor;
import com.github.wrossmorrow.extproc.processors.DigestRequestProcessor;
import com.google.protobuf.ByteString;
import io.grpc.Status;
import io.grpc.stub.StreamObserver;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class ExampleProcessorsConcurrencyTest {

  private static final class RecordingObserver implements StreamObserver<ProcessingResponse> {
    final List<ProcessingResponse> responses =
        Collections.synchronizedList(new ArrayList<ProcessingResponse>());

    @Override
    public void onNext(ProcessingResponse value) {
      responses.add(value);
    }

    @Override
    public void onError(Throwable t) {
      throw new AssertionError("unexpected stream error", t);
    }

    @Override
    public void onCompleted() {}
  }

  private static ProcessingRequest requestHeaders(
      String method, String path, String requestId, boolean endOfStream) {
    HeaderMap.Builder headers =
        HeaderMap.newBuilder()
            .addHeaders(HeaderValue.newBuilder().setKey(":method").setValue(method))
            .addHeaders(HeaderValue.newBuilder().setKey(":path").setValue(path));
    if (requestId != null) {
      headers.addHeaders(HeaderValue.newBuilder().setKey("x-request-id").setValue(requestId));
    }
    return ProcessingRequest.newBuilder()
        .setRequestHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(headers.build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static ProcessingRequest requestBody(String body, boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setRequestBody(
            HttpBody.newBuilder()
                .setBody(ByteString.copyFrom(body, StandardCharsets.UTF_8))
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static ProcessingRequest responseHeaders(boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setResponseHeaders(
            HttpHeaders.newBuilder()
                .setHeaders(
                    HeaderMap.newBuilder()
                        .addHeaders(HeaderValue.newBuilder().setKey(":status").setValue("200"))
                        .build())
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static ProcessingRequest responseBody(String body, boolean endOfStream) {
    return ProcessingRequest.newBuilder()
        .setResponseBody(
            HttpBody.newBuilder()
                .setBody(ByteString.copyFrom(body, StandardCharsets.UTF_8))
                .setEndOfStream(endOfStream)
                .build())
        .build();
  }

  private static ProcessingRequest responseTrailers() {
    return ProcessingRequest.newBuilder()
        .setResponseTrailers(
            HttpTrailers.newBuilder().setTrailers(HeaderMap.getDefaultInstance()).build())
        .build();
  }

  private static String findHeaderValue(List<HeaderValueOption> options, String key) {
    for (HeaderValueOption opt : options) {
      if (key.equalsIgnoreCase(opt.getHeader().getKey())) {
        if (!opt.getHeader().getRawValue().isEmpty()) {
          return opt.getHeader().getRawValue().toStringUtf8();
        }
        return opt.getHeader().getValue();
      }
    }
    return null;
  }

  private static String sha256Hex(String input) throws Exception {
    byte[] digest =
        MessageDigest.getInstance("SHA-256").digest(input.getBytes(StandardCharsets.UTF_8));
    StringBuilder sb = new StringBuilder(digest.length * 2);
    for (byte b : digest) {
      sb.append(Character.forDigit((b >>> 4) & 0x0F, 16));
      sb.append(Character.forDigit(b & 0x0F, 16));
    }
    return sb.toString();
  }

  @Test
  void dedupAllowsExactlyOneWinnerUnderConcurrentIdenticalRequests() throws Exception {
    final int threads = 32;
    final DedupRequestProcessor processor = new DedupRequestProcessor();
    final ExternalProcessor extProc = new ExternalProcessor(processor, null);
    final CyclicBarrier bodyBarrier = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      List<Future<StreamOutcome>> futures = new ArrayList<Future<StreamOutcome>>(threads);
      for (int i = 0; i < threads; i++) {
        final String reqId = "req-" + i;
        futures.add(
            pool.submit(
                new Callable<StreamOutcome>() {
                  @Override
                  public StreamOutcome call() throws Exception {
                    RecordingObserver observer = new RecordingObserver();
                    StreamObserver<ProcessingRequest> stream = extProc.process(observer);
                    stream.onNext(requestHeaders("POST", "/orders", reqId, false));
                    bodyBarrier.await(10, TimeUnit.SECONDS);
                    stream.onNext(requestBody("{\"sku\":\"abc\"}", true));
                    return new StreamOutcome(reqId, stream, observer);
                  }
                }));
      }

      StreamOutcome winner = null;
      List<StreamOutcome> rejected = new ArrayList<StreamOutcome>();
      for (Future<StreamOutcome> future : futures) {
        StreamOutcome outcome = future.get(15, TimeUnit.SECONDS);
        assertEquals(2, outcome.observer.responses.size());
        ProcessingResponse bodyResp = outcome.observer.responses.get(1);
        if (bodyResp.hasImmediateResponse()) {
          rejected.add(outcome);
        } else {
          assertTrue(bodyResp.hasRequestBody());
          assertEquals(null, winner, "Expected only one winning stream");
          winner = outcome;
        }
      }

      assertTrue(winner != null, "Expected one winning stream");
      assertEquals(threads - 1, rejected.size());
      for (StreamOutcome dup : rejected) {
        ProcessingResponse resp = dup.observer.responses.get(1);
        assertEquals(StatusCode.Conflict, resp.getImmediateResponse().getStatus().getCode());
        String body = resp.getImmediateResponse().getBody().toStringUtf8();
        assertTrue(body.contains("\"requestId\":\"" + winner.requestId + "\""), body);
      }

      // Completing the winner via response headers releases the in-flight entry.
      winner.stream.onNext(responseHeaders(true));
      winner.stream.onCompleted();

      RecordingObserver followUpObs = new RecordingObserver();
      StreamObserver<ProcessingRequest> followUp = extProc.process(followUpObs);
      followUp.onNext(requestHeaders("POST", "/orders", "req-after", false));
      followUp.onNext(requestBody("{\"sku\":\"abc\"}", true));
      assertTrue(followUpObs.responses.get(1).hasRequestBody());
      assertFalse(followUpObs.responses.get(1).hasImmediateResponse());
      followUp.onNext(responseHeaders(true));
      followUp.onCompleted();
    } finally {
      pool.shutdownNow();
    }
  }

  @Test
  void dedupReleasesInflightOnCancellationTrailersAndMissingRequestId() {
    DedupRequestProcessor processor = new DedupRequestProcessor();
    ExternalProcessor extProc = new ExternalProcessor(processor, null);

    // 1. Stream without x-request-id succeeds and blocks a duplicate with 409.
    RecordingObserver obs1 = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream1 = extProc.process(obs1);
    stream1.onNext(requestHeaders("POST", "/no-id", null, false));
    stream1.onNext(requestBody("payload", true));
    assertTrue(obs1.responses.get(1).hasRequestBody());

    RecordingObserver obs2 = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream2 = extProc.process(obs2);
    stream2.onNext(requestHeaders("POST", "/no-id", null, false));
    stream2.onNext(requestBody("payload", true));
    assertTrue(obs2.responses.get(1).hasImmediateResponse());
    assertEquals(
        StatusCode.Conflict, obs2.responses.get(1).getImmediateResponse().getStatus().getCode());

    // 2. Cancelling the rejected duplicate must not evict stream1's in-flight lock.
    stream2.onError(Status.CANCELLED.asRuntimeException());

    RecordingObserver obs3 = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream3 = extProc.process(obs3);
    stream3.onNext(requestHeaders("POST", "/no-id", "dup-check", false));
    stream3.onNext(requestBody("payload", true));
    assertTrue(obs3.responses.get(1).hasImmediateResponse());
    stream3.onError(Status.CANCELLED.asRuntimeException());

    // 3. Cancelling the winning stream releases the in-flight lock via errorHandler.
    stream1.onError(Status.CANCELLED.asRuntimeException());

    RecordingObserver obs4 = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream4 = extProc.process(obs4);
    stream4.onNext(requestHeaders("POST", "/no-id", "after-cancel", false));
    stream4.onNext(requestBody("payload", true));
    assertTrue(obs4.responses.get(1).hasRequestBody());

    // 4. Completing response via trailers (with headers/body endOfStream=false) releases the lock.
    stream4.onNext(responseHeaders(false));
    stream4.onNext(responseBody("resp", false));
    stream4.onNext(responseTrailers());
    stream4.onCompleted();

    RecordingObserver obs5 = new RecordingObserver();
    StreamObserver<ProcessingRequest> stream5 = extProc.process(obs5);
    stream5.onNext(requestHeaders("POST", "/no-id", "after-trailers", false));
    stream5.onNext(requestBody("payload", true));
    assertTrue(obs5.responses.get(1).hasRequestBody());
    stream5.onNext(responseHeaders(true));
    stream5.onCompleted();
  }

  @Test
  void digestIsolatesConcurrentStreamsIncludingMissingRequestIdAndLeadingZeroHex()
      throws Exception {
    final int threads = 32;
    final DigestRequestProcessor processor = new DigestRequestProcessor();
    final ExternalProcessor extProc = new ExternalProcessor(processor, null);
    final CyclicBarrier startBarrier = new CyclicBarrier(threads);
    ExecutorService pool = Executors.newFixedThreadPool(threads);

    try {
      List<Future<Void>> futures = new ArrayList<Future<Void>>(threads);
      for (int i = 0; i < threads; i++) {
        final int idx = i;
        final String reqId = (idx % 2 == 0) ? ("digest-req-" + idx) : null;
        final String path = "/items/" + idx;
        final String chunk1 = "chunk-a-" + idx;
        final String chunk2 = "chunk-b-" + idx;
        final String expectedDigest = sha256Hex("POST:" + path + ":" + chunk1 + chunk2);
        assertEquals(64, expectedDigest.length());

        futures.add(
            pool.submit(
                new Callable<Void>() {
                  @Override
                  public Void call() throws Exception {
                    startBarrier.await(10, TimeUnit.SECONDS);
                    RecordingObserver obs = new RecordingObserver();
                    StreamObserver<ProcessingRequest> stream = extProc.process(obs);
                    stream.onNext(requestHeaders("POST", path, reqId, false));
                    stream.onNext(requestBody(chunk1, false));
                    stream.onNext(requestBody(chunk2, true));
                    stream.onNext(responseHeaders(idx % 3 == 0));
                    if (idx % 3 != 0) {
                      stream.onNext(responseBody("ok", false));
                      stream.onNext(responseTrailers());
                    }
                    stream.onCompleted();

                    String reqDigest =
                        findHeaderValue(
                            obs.responses
                                .get(2)
                                .getRequestBody()
                                .getResponse()
                                .getHeaderMutation()
                                .getSetHeadersList(),
                            "x-extproc-request-digest");
                    assertEquals(expectedDigest, reqDigest);

                    ProcessingResponse finalResp = obs.responses.get(obs.responses.size() - 1);
                    String respDigest =
                        findHeaderValue(
                            idx % 3 == 0
                                ? finalResp
                                    .getResponseHeaders()
                                    .getResponse()
                                    .getHeaderMutation()
                                    .getSetHeadersList()
                                : finalResp
                                    .getResponseTrailers()
                                    .getHeaderMutation()
                                    .getSetHeadersList(),
                            "x-extproc-request-digest");
                    assertEquals(expectedDigest, respDigest);
                    return null;
                  }
                }));
      }

      for (Future<Void> future : futures) {
        future.get(15, TimeUnit.SECONDS);
      }
    } finally {
      pool.shutdownNow();
    }

    // Verify full 64-character hex padding for leading-zero SHA-256 digests.
    byte[] allZero = new byte[32];
    byte[] leadingZero = new byte[32];
    leadingZero[1] = 0x0f;
    leadingZero[31] = (byte) 0xab;

    class ExposedDigest extends DigestRequestProcessor {
      String hex(byte[] b) {
        return convertToHex(b);
      }
    }
    ExposedDigest exposedDigest = new ExposedDigest();
    String zeroHex = exposedDigest.hex(allZero);
    assertEquals(64, zeroHex.length());
    assertTrue(zeroHex.startsWith("0000"));
    String paddedHex = exposedDigest.hex(leadingZero);
    assertEquals(64, paddedHex.length());
    assertTrue(paddedHex.startsWith("000f"));
    assertTrue(paddedHex.endsWith("ab"));
  }

  private static final class StreamOutcome {
    final String requestId;
    final StreamObserver<ProcessingRequest> stream;
    final RecordingObserver observer;

    StreamOutcome(
        String requestId, StreamObserver<ProcessingRequest> stream, RecordingObserver observer) {
      this.requestId = requestId;
      this.stream = stream;
      this.observer = observer;
    }
  }
}
