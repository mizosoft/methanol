/*
 * Copyright (c) 2019-2021 Moataz Abdelnasser
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
import java.net.http.HttpResponse;
import java.net.http.HttpResponse.BodyHandler;
import java.net.http.HttpResponse.BodyHandlers;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.PushPromiseHandler;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.function.Function;

/**
 * Utilities for handling an {@code HttpResponse<Publisher<List<ByteBuffer>>>} to obtain an {@code
 * HttpResponse<T>}.
 */
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
      Publisher<List<ByteBuffer>> bodyPublisher,
      BodyHandler<T> handler,
      Executor executor) {
    var subscriberFuture =
        CompletableFuture.supplyAsync(
            () -> handler.apply(ImmutableResponseInfo.from(response)), executor);
    subscriberFuture.thenAcceptAsync(bodyPublisher::subscribe, executor);
    return subscriberFuture
        .thenComposeAsync(BodySubscriber::getBody, executor)
        .thenApply(body -> ResponseBuilder.newBuilder(response).body(body).build());
  }

  public static <T> Chain<Publisher<List<ByteBuffer>>> toPublisherChain(
      Chain<T> chain, Executor handlerExecutor) {
    var screenedPushPromiseHandler =
        chain
            .pushPromiseHandler()
            .map(pushHandler -> screenPushPromiseHandler(pushHandler, handlerExecutor))
            .orElse(null);
    return chain.with(BodyHandlers.ofPublisher(), screenedPushPromiseHandler);
  }

  /**
   * Returns a publisher-based {@code PushPromiseHandler} that relays to the given downstream {@code
   * PushPromiseHandler} and handles the bodies of accepted push promises using the correct response
   * type.
   *
   * @param executor used to invoke the body handlers of accepted push promises
   */
  private static <T> PushPromiseHandler<Publisher<List<ByteBuffer>>> screenPushPromiseHandler(
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
