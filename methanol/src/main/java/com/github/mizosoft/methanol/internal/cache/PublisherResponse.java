package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.extensions.ImmutableResponseInfo;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;

class PublisherResponse extends RawResponse {
  private final Publisher<List<ByteBuffer>> publisher;

  PublisherResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> publisher) {
    super(response);
    this.publisher = publisher;
  }

  @Override
  public <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor) {
    var subscriberFuture =
        CompletableFuture.supplyAsync(
            () -> handler.apply(ImmutableResponseInfo.from(response)), executor);
    subscriberFuture.thenAcceptAsync(publisher::subscribe, executor);
    return subscriberFuture
        .thenComposeAsync(BodySubscriber::getBody, executor)
        .thenApply(body -> ResponseBuilder.newBuilder(response).body(body).build());
  }
}
