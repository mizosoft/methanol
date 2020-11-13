package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;

/** A response with a "raw" body that is yet to be handled. */
public abstract class RawResponse {
  final TrackedResponse<?> response;

  RawResponse(TrackedResponse<?> response) {
    this.response = response;
  }

  public TrackedResponse<?> get() {
    return response;
  }

  public <T> TrackedResponse<T> handle(BodyHandler<T> handler)
      throws IOException, InterruptedException {
    try {
      return handleAsync(handler, FlowSupport.SYNC_EXECUTOR).get();
    } catch (ExecutionException e) {
      // TODO decode execution exception
      throw new RuntimeException(e.getCause());
    }
  }

  public abstract <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor);

  public static RawResponse from(TrackedResponse<Publisher<List<ByteBuffer>>> response) {
    return new PublisherResponse(response, response.body());
  }
}
