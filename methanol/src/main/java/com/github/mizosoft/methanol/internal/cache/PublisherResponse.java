package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.extensions.ImmutableResponseInfo;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Consumer;

class PublisherResponse extends RawResponse {
  final Publisher<List<ByteBuffer>> publisher;

  PublisherResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> publisher) {
    super(dropBody(response));
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

  @Override
  public RawResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new PublisherResponse(builder.build(), publisher);
  }

  private static TrackedResponse<?> dropBody(TrackedResponse<?> response) {
    return response.body() != null
        ? ResponseBuilder.newBuilder(response).dropBody().build()
        : response;
  }
}
