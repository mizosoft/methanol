/*
 * Copyright (c) 2023 Moataz Abdelnasser
 *
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 *
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.internal.extensions;

import com.github.mizosoft.methanol.Methanol.Interceptor.Chain;
import com.github.mizosoft.methanol.ResponseBuilder;
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Function;

/** Static functions for converting the response body into a usable body type. */
public class Handlers {
  private Handlers() {}

  public static <T> CompletableFuture<HttpResponse<T>> handleAsync(
      HttpResponse<Publisher<List<ByteBuffer>>> response,
      BodyHandler<T> handler,
      Executor executor) {
    return handleAsync(response, response.body(), handler, executor);
  }

  public static <T> CompletableFuture<HttpResponse<T>> handleAsync(
      HttpResponse<?> response,
      Publisher<List<ByteBuffer>> publisher,
      BodyHandler<T> handler,
      Executor executor) {
    return handleAsync(ImmutableResponseInfo.from(response), publisher, handler, executor)
        .thenApply(body -> ResponseBuilder.newBuilder(response).body(body).build());
  }

  public static <T> CompletableFuture<T> handleAsync(
      ResponseInfo responseInfo,
      Publisher<List<ByteBuffer>> publisher,
      BodyHandler<T> handler,
      Executor executor) {
    var subscriber = handler.apply(responseInfo);

    // Publisher::subscribe can initiate body flow synchronously, which might block.
    CompletableFuture.runAsync(() -> publisher.subscribe(subscriber), executor);

    // BodySubscriber::getBody can block (see BodySubscribers::mapping's javadoc).
    return CompletableFuture.supplyAsync(subscriber::getBody, executor)
        .thenCompose(Function.identity());
  }

  public static <T> Chain<Publisher<List<ByteBuffer>>> toPublisherChain(
      Chain<T> chain, Executor executor) {
    var relayingPushPromiseHandler =
        chain
            .pushPromiseHandler()
            .map(pushHandler -> toRelayingPushPromiseHandler(pushHandler, executor))
            .orElse(null);
    return chain.with(BodyHandlers.ofPublisher(), relayingPushPromiseHandler);
  }

  /**
   * Returns a publisher-based {@code PushPromiseHandler} that relays to the given downstream {@code
   * PushPromiseHandler} and handles the bodies of accepted push promises using the correct response
   * type.
   */
  private static <T> PushPromiseHandler<Publisher<List<ByteBuffer>>> toRelayingPushPromiseHandler(
      PushPromiseHandler<T> downstreamPushPromiseHandler, Executor executor) {
    return (initiatingRequest, pushPromiseRequest, acceptor) -> {
      Function<BodyHandler<T>, CompletableFuture<HttpResponse<T>>> downstreamAcceptor =
          bodyHandler ->
              acceptor
                  .apply(BodyHandlers.ofPublisher())
                  .thenCompose(response -> handleAsync(response, bodyHandler, executor));
      downstreamPushPromiseHandler.applyPushPromise(
          initiatingRequest, pushPromiseRequest, downstreamAcceptor);
    };
  }
}
