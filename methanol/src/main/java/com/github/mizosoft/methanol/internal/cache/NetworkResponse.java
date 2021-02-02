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

package com.github.mizosoft.methanol.internal.cache;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.cache.Store.Editor;
import com.github.mizosoft.methanol.internal.extensions.ResponseBuilder;
import com.github.mizosoft.methanol.internal.extensions.TrackedResponse;
import com.github.mizosoft.methanol.internal.flow.Upstream;
import java.net.http.HttpResponse.BodySubscriber;
import java.net.http.HttpResponse.ResponseInfo;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscription;
import java.util.function.Consumer;

/** A {@code RawResponse} that came from the network and maybe cached. */
public final class NetworkResponse extends PublisherResponse {
  NetworkResponse(TrackedResponse<?> response, Publisher<List<ByteBuffer>> publisher) {
    super(response, publisher);
  }

  public NetworkResponse cachingWith(Editor editor, ByteBuffer metadata) {
    var writingSubscriber = new CacheWritingBodySubscriber(editor, metadata);
    publisher.subscribe(writingSubscriber);
    return new NetworkResponse(response, writingSubscriber.getBody().toCompletableFuture().join());
  }

  /** Asynchronously drains the entire response body. */
  public void drainInBackground(Executor executor) {
    handleAsync(NetworkResponse::draining, executor);
  }

  @Override
  public NetworkResponse with(Consumer<ResponseBuilder<?>> mutator) {
    var builder = ResponseBuilder.newBuilder(response);
    mutator.accept(builder);
    return new NetworkResponse(builder.build(), publisher);
  }

  private static BodySubscriber<Void> draining(ResponseInfo unused) {
    // Make sure the upstream is drained
    return new BodySubscriber<>() {
      private final Upstream upstream = new Upstream();

      @Override
      public CompletionStage<Void> getBody() {
        return CompletableFuture.completedFuture(null); // Drain in background
      }

      @Override
      public void onSubscribe(Subscription subscription) {
        if (upstream.setOrCancel(subscription)) {
          subscription.request(Long.MAX_VALUE);
        }
      }

      @Override
      public void onNext(List<ByteBuffer> item) {
        requireNonNull(item);
      }

      @Override
      public void onError(Throwable throwable) {
        requireNonNull(throwable);
      }

      @Override
      public void onComplete() {}
    };
  }

  public static NetworkResponse from(TrackedResponse<Publisher<List<ByteBuffer>>> response) {
    return new NetworkResponse(response, response.body());
  }
}
