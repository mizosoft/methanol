package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.TrackedResponse;
import com.github.mizosoft.methanol.internal.extensions.Handlers;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import java.net.http.HttpResponse.BodyHandler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;

abstract class PublisherResponse extends RawResponse {
  final Publisher<List<ByteBuffer>> publisher;

  PublisherResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> publisher) {
    super(dropBody(response));
    this.publisher = publisher;
  }

  @Override
  public <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor) {
    // Result will be a TrackedResponse as source response is itself tracked
    return Handlers.handleAsync(response, publisher, handler, executor)
        .thenApply(response -> (TrackedResponse<T>) response);
  }

  private static TrackedResponse<?> dropBody(TrackedResponse<?> response) {
    return response.body() != null
        ? ResponseBuilder.newBuilder(response).dropBody().buildTracked()
        : response;
  }
}
