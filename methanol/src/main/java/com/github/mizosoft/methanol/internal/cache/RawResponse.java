package com.github.mizosoft.methanol.internal.cache;

import com.github.mizosoft.methanol.internal.Utils;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.io.IOException;
import java.net.http.HttpResponse.BodyHandler;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;

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
    return Utils.block(handleAsync(handler, FlowSupport.SYNC_EXECUTOR));
  }

  public abstract <T> CompletableFuture<TrackedResponse<T>> handleAsync(
      BodyHandler<T> handler, Executor executor);

  public abstract RawResponse with(Consumer<ResponseBuilder<?>> mutator);
}
