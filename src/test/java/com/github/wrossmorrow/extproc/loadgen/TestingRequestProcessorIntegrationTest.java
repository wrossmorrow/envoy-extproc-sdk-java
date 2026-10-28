package com.github.wrossmorrow.extproc.loadgen;

import static org.junit.jupiter.api.Assertions.*;

import build.buf.gen.envoy.config.core.v3.HeaderValue;
import build.buf.gen.envoy.service.ext_proc.v3.CommonResponse;
import build.buf.gen.envoy.service.ext_proc.v3.ExternalProcessorGrpc;
import build.buf.gen.envoy.service.ext_proc.v3.HttpBody;
import build.buf.gen.envoy.service.ext_proc.v3.HttpHeaders;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingRequest;
import build.buf.gen.envoy.service.ext_proc.v3.ProcessingResponse;
import com.github.wrossmorrow.extproc.ExternalProcessor;
import com.github.wrossmorrow.extproc.processors.TestingRequestProcessor;
import com.google.protobuf.ByteString;
import io.grpc.ManagedChannel;
import io.grpc.ManagedChannelBuilder;
import io.grpc.Server;
import io.grpc.ServerBuilder;
import io.grpc.stub.StreamObserver;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Integration tests driving full ext_proc exchanges through the SDK's gRPC external processor
 * service, using the {@link TestingRequestProcessor} as the processor under test (the Java analog
 * of the Go SDK's tester harness).
 */
class TestingRequestProcessorIntegrationTest {

  private ManagedChannel channel;
  private Server server;
  private ExternalProcessorGrpc.ExternalProcessorStub stub;

  @BeforeEach
  void setUp() throws Exception {
    ExternalProcessor processor = new ExternalProcessor(new TestingRequestProcessor(), null);
    ServerBuilder<?> serverBuilder =
        io.grpc.Grpc.newServerBuilderForPort(0, io.grpc.InsecureServerCredentials.create());
    server = serverBuilder.addService(processor).build();
    server.start();
    channel =
        ManagedChannelBuilder.forAddress("localhost", server.getPort()).usePlaintext().build();
    stub = ExternalProcessorGrpc.newStub(channel);
  }

  @AfterEach
  void tearDown() {
    if (channel != null) {
      channel.shutdown();
    }
    if (server != null) {
      server.shutdownNow();
    }
  }

  /** Drive one full exchange and return the collected responses (one per phase, in order). */
  private List<ProcessingResponse> exchange(String requestBodyJson, boolean sendResponsePhases) {
    LoadGen.ResponseCollector responseObserver = new LoadGen.ResponseCollector();
    StreamObserver<ProcessingRequest> requestObserver = stub.process(responseObserver);

    // phase 1: request headers (not yet end of stream)
    responseObserver.latch = new CountDownLatch(1);
    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestHeaders(
                HttpHeaders.newBuilder()
                    .setHeaders(LoadGen.requestHeaderMap("POST", "/test"))
                    .setEndOfStream(false)
                    .build())
            .build());
    LoadGen.awaitExchange(responseObserver);
    List<ProcessingResponse> responses = new ArrayList<>();
    responses.add(responseObserver.lastResponse);

    // phase 2: request body (end of stream)
    responseObserver.latch = new CountDownLatch(1);
    requestObserver.onNext(
        ProcessingRequest.newBuilder()
            .setRequestBody(
                HttpBody.newBuilder()
                    .setBody(ByteString.copyFromUtf8(requestBodyJson))
                    .setEndOfStream(true)
                    .build())
            .build());
    LoadGen.awaitExchange(responseObserver);
    responses.add(responseObserver.lastResponse);

    if (sendResponsePhases) {
      // phase 3: response headers (end of stream)
      responseObserver.latch = new CountDownLatch(1);
      requestObserver.onNext(
          ProcessingRequest.newBuilder()
              .setResponseHeaders(HttpHeaders.newBuilder().setEndOfStream(true).build())
              .build());
      LoadGen.awaitExchange(responseObserver);
      responses.add(responseObserver.lastResponse);

      // phase 4: response body (end of stream)
      responseObserver.latch = new CountDownLatch(1);
      requestObserver.onNext(
          ProcessingRequest.newBuilder()
              .setResponseBody(
                  HttpBody.newBuilder()
                      .setBody(ByteString.copyFromUtf8("{}"))
                      .setEndOfStream(true)
                      .build())
              .build());
      requestObserver.onCompleted();
      LoadGen.awaitExchange(responseObserver);
      responses.add(responseObserver.lastResponse);
    }

    return responses;
  }

  @Test
  void noopExchangeCompletesWithoutError() {
    List<ProcessingResponse> responses = exchange("{}", true);
    assertEquals(ProcessingResponse.ResponseCase.RESPONSE_BODY, responses.get(3).getResponseCase());
  }

  @Test
  void requestHeaderActionsAreApplied() {
    List<ProcessingResponse> responses =
        exchange("{\"add_request_headers\":{\"x-add-me\":\"true\"}}", true);
    // phase 2 (request body) is where request header actions are applied; the phase
    // response for the request body phase carries the request body mutations
    ProcessingResponse response = responses.get(1);
    assertEquals(ProcessingResponse.ResponseCase.REQUEST_BODY, response.getResponseCase());
    CommonResponse common = response.getRequestBody().getResponse();
    boolean found = false;
    for (build.buf.gen.envoy.config.core.v3.HeaderValueOption option :
        common.getHeaderMutation().getSetHeadersList()) {
      HeaderValue header = option.getHeader();
      if (header != null && "x-add-me".equalsIgnoreCase(header.getKey())) {
        assertEquals("true", header.getValue());
        found = true;
      }
    }
    assertTrue(found, "expected x-add-me to be added via the request header action");
  }

  @Test
  void responseHeaderActionsAreApplied() {
    List<ProcessingResponse> responses =
        exchange("{\"add_response_headers\":{\"x-add-me\":\"true\"}}", true);
    // phase 3 (response headers) is where response header actions are applied
    ProcessingResponse response = responses.get(2);
    assertEquals(ProcessingResponse.ResponseCase.RESPONSE_HEADERS, response.getResponseCase());
    CommonResponse common = response.getResponseHeaders().getResponse();
    boolean found = false;
    for (build.buf.gen.envoy.config.core.v3.HeaderValueOption option :
        common.getHeaderMutation().getSetHeadersList()) {
      HeaderValue header = option.getHeader();
      if (header != null && "x-add-me".equalsIgnoreCase(header.getKey())) {
        assertEquals("true", header.getValue());
        found = true;
      }
    }
    assertTrue(found, "expected x-add-me to be added via the response header action");
  }

  @Test
  void requestBodyReplacementIsApplied() {
    List<ProcessingResponse> responses = exchange("{\"replace_request_body\":\"hello\"}", true);
    // the request-body action lands in the phase-2 (request body) response, which is
    // the response phase that carries the new request body
    ProcessingResponse response = responses.get(1);
    assertEquals(ProcessingResponse.ResponseCase.REQUEST_BODY, response.getResponseCase());
    CommonResponse common = response.getRequestBody().getResponse();
    assertEquals("hello", common.getBodyMutation().getBody().toStringUtf8());
  }

  @Test
  void cancelRequestIsApplied() {
    List<ProcessingResponse> responses =
        exchange(
            "{\"cancel_request\":true,\"cancel_request_status\":409,\"cancel_request_body\":\"boom\"}",
            false);
    // the cancel lands in the phase-2 (request body) response as an immediate response
    ProcessingResponse response = responses.get(1);
    assertNotNull(response.getImmediateResponse());
    assertEquals(409, response.getImmediateResponse().getStatus().getCodeValue());
    assertEquals("boom", response.getImmediateResponse().getBody());
  }
}
