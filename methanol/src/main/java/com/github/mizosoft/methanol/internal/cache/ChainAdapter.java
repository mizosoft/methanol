package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.internal.concurrent.CancellationPropagatingFuture;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Flow.Publisher;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * An object that masks synchronous chain calls as {@code CompletableFuture} calls that are executed
 * on the caller thread. This is important in order to share major logic between {@code intercept}
 * and {@code interceptAsync}, which facilitates implementation & maintenance.
 */
final class ChainAdapter {
  private final Chain<Publisher<List<ByteBuffer>>> chain;
  private final @Nullable Thread syncCallerThread;

  private ChainAdapter(
      Chain<Publisher<List<ByteBuffer>>> chain, @Nullable Thread syncCallerThread) {
    this.chain = requireNonNull(chain);
    this.syncCallerThread = syncCallerThread;
  }

  CompletableFuture<HttpResponse<Publisher<List<ByteBuffer>>>> forward(HttpRequest request) {
    if (syncCallerThread == null || syncCallerThread != Thread.currentThread()) {
      return CancellationPropagatingFuture.of(chain.forwardAsync(request));
    }

    try {
      return CompletableFuture.completedFuture(chain.forward(request));
    } catch (Throwable t) {
      return CompletableFuture.failedFuture(t);
    }
  }

  public boolean isAsync() {
    return syncCallerThread == null;
  }

  public Chain<Publisher<List<ByteBuffer>>> chain() {
    return chain;
  }

  static ChainAdapter async(Chain<Publisher<List<ByteBuffer>>> chain) {
    return new ChainAdapter(chain, null);
  }

  static ChainAdapter syncOnCaller(Chain<Publisher<List<ByteBuffer>>> chain) {
    return new ChainAdapter(chain, Thread.currentThread());
  }
}
