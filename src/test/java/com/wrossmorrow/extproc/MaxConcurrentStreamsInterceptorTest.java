package com.github.wrossmorrow.extproc;

import static org.junit.jupiter.api.Assertions.*;

import io.grpc.Metadata;
import io.grpc.MethodDescriptor;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class MaxConcurrentStreamsInterceptorTest {

  private static class TestCall extends ServerCall<Object, Object> {
    TestCall() {}

    @Override
    public void request(int numMessages) {}

    @Override
    public void sendHeaders(Metadata headers) {}

    @Override
    public void sendMessage(Object message) {}

    @Override
    public void close(Status status, Metadata trailers) {}

    @Override
    public boolean isCancelled() {
      return false;
    }

    @Override
    public boolean isReady() {
      return true;
    }

    @Override
    public MethodDescriptor<Object, Object> getMethodDescriptor() {
      return null;
    }
  }

  private static class TestHandler implements ServerCallHandler<Object, Object> {
    final AtomicInteger started = new AtomicInteger();
    final AtomicInteger messages = new AtomicInteger();

    @Override
    public ServerCall.Listener<Object> startCall(
        ServerCall<Object, Object> call, Metadata headers) {
      started.incrementAndGet();
      return new ServerCall.Listener<Object>() {
        @Override
        public void onMessage(Object message) {
          messages.incrementAndGet();
        }

        @Override
        public void onHalfClose() {}

        @Override
        public void onCancel() {}

        @Override
        public void onComplete() {}

        @Override
        public void onReady() {}
      };
    }
  }

  @Test
  void allowsUpToMaxConcurrentStreams() {
    MaxConcurrentStreamsInterceptor interceptor = new MaxConcurrentStreamsInterceptor(2);
    TestHandler handler = new TestHandler();
    ServerCall<Object, Object> call = new TestCall();

    ServerCall.Listener<Object> listener1 =
        interceptor.interceptCall(call, new Metadata(), handler);
    ServerCall.Listener<Object> listener2 =
        interceptor.interceptCall(call, new Metadata(), handler);
    assertEquals(2, handler.started.get());

    ServerCall.Listener<Object> listener3 =
        interceptor.interceptCall(call, new Metadata(), handler);
    assertEquals(2, handler.started.get());
  }

  @Test
  void rejectsBeyondMaxConcurrentStreams() {
    MaxConcurrentStreamsInterceptor interceptor = new MaxConcurrentStreamsInterceptor(1);
    TestHandler handler = new TestHandler();
    ServerCall<Object, Object> call = new TestCall();

    ServerCall.Listener<Object> listener1 =
        interceptor.interceptCall(call, new Metadata(), handler);
    assertEquals(1, handler.started.get());

    ServerCall.Listener<Object> listener2 =
        interceptor.interceptCall(call, new Metadata(), handler);
    assertEquals(1, handler.started.get());
  }

  @Test
  void allowsUnlimitedWhenMaxIsZero() {
    MaxConcurrentStreamsInterceptor interceptor = new MaxConcurrentStreamsInterceptor(0);
    TestHandler handler = new TestHandler();
    ServerCall<Object, Object> call = new TestCall();

    ServerCall.Listener<Object> listener1 =
        interceptor.interceptCall(call, new Metadata(), handler);
    ServerCall.Listener<Object> listener2 =
        interceptor.interceptCall(call, new Metadata(), handler);
    assertEquals(2, handler.started.get());
  }
}
