package com.github.wrossmorrow.extproc;

import io.grpc.Metadata;
import io.grpc.ServerCall;
import io.grpc.ServerCallHandler;
import io.grpc.ServerInterceptor;
import io.grpc.Status;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Server interceptor that enforces a maximum number of concurrent processing streams. When the
 * limit has been reached, the incoming stream is rejected with a {@link Status#RESOURCE_EXHAUSTED}.
 *
 * <p>Note that the gRPC server itself does not expose a "max concurrent streams" server option
 * (unlike e.g. grpc-go's {@code grpc.MaxConcurrentStreams}), so the limit is enforced here at the
 * application level. A limit of zero (or less) means unlimited.
 */
class MaxConcurrentStreamsInterceptor implements ServerInterceptor {

  private final int maxConcurrentStreams;
  private final AtomicInteger activeStreams = new AtomicInteger();

  MaxConcurrentStreamsInterceptor(int maxConcurrentStreams) {
    this.maxConcurrentStreams = maxConcurrentStreams;
  }

  @Override
  public <ReqT, RespT> ServerCall.Listener<ReqT> interceptCall(
      ServerCall<ReqT, RespT> call, Metadata headers, ServerCallHandler<ReqT, RespT> handler) {
    if (maxConcurrentStreams > 0 && activeStreams.get() >= maxConcurrentStreams) {
      call.close(
          Status.RESOURCE_EXHAUSTED.withDescription("max concurrent streams exceeded"),
          new Metadata());
      return new ServerCall.Listener<ReqT>() {
        @Override
        public void onMessage(ReqT message) {}

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

    final AtomicBoolean released = new AtomicBoolean(false);
    final ServerCall.Listener<ReqT> listener = handler.startCall(call, headers);
    activeStreams.incrementAndGet();
    return new ServerCall.Listener<ReqT>() {
      @Override
      public void onMessage(ReqT message) {
        listener.onMessage(message);
      }

      @Override
      public void onHalfClose() {
        if (released.compareAndSet(false, true)) {
          release();
        }
        listener.onHalfClose();
      }

      @Override
      public void onCancel() {
        if (released.compareAndSet(false, true)) {
          release();
        }
        listener.onCancel();
      }

      @Override
      public void onComplete() {
        if (released.compareAndSet(false, true)) {
          release();
        }
        listener.onComplete();
      }

      @Override
      public void onReady() {
        listener.onReady();
      }
    };
  }

  private void release() {
    activeStreams.decrementAndGet();
  }

  int getActiveStreams() {
    return activeStreams.get();
  }
}
