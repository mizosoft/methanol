/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.net.http.HttpResponse.BodySubscriber;
import java.nio.ByteBuffer;
import java.util.List;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * Adapts a subscriber to a {@code BodySubscriber} where the body's completion need not be in
 * accordance with {@code onComplete} or {@code onError}.
 *
 * @param <T> the body type
 * @param <S> the subscriber's type
 */
public class AsyncSubscriberAdapter<T, S extends Subscriber<? super List<ByteBuffer>>>
    implements BodySubscriber<T> {

  private final S downstream;
  private final Function<? super S, ? extends CompletionStage<T>> asyncFinisher;
  private final AtomicReference<@Nullable Subscription> upstream;
  // Safety flag to ensure that deferred cancellation from upstream doesn't
  // cause the downstream to receive post-completion signals in case downstream's
  // onSubscribe or onNext threw previously
  private volatile boolean completed;

  public AsyncSubscriberAdapter(
      S downstream, Function<? super S, ? extends CompletionStage<T>> asyncFinisher) {
    this.downstream = requireNonNull(downstream, "downstream");
    this.asyncFinisher = requireNonNull(asyncFinisher, "asyncFinisher");
    upstream = new AtomicReference<>();
  }

  @Override
  public CompletionStage<T> getBody() {
    return asyncFinisher.apply(downstream);
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.compareAndSet(null, subscription)) {
      try {
        downstream.onSubscribe(subscription);
      } catch (Throwable t) {
        complete(t, true);
      }
    } else {
      subscription.cancel();
    }
  }

  @Override
  public void onNext(List<ByteBuffer> item) {
    requireNonNull(item);
    if (!completed) {
      try {
        downstream.onNext(item);
      } catch (Throwable t) {
        complete(t, true);
      }
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    complete(throwable, false);
  }

  @Override
  public void onComplete() {
    complete(null, false);
  }

  private void clearUpstream(boolean cancel) {
    if (cancel) {
      Subscription s = upstream.getAndSet(FlowSupport.NOOP_SUBSCRIPTION);
      if (s != null) {
        s.cancel();
      }
    } else {
      upstream.set(FlowSupport.NOOP_SUBSCRIPTION);
    }
  }

  private void complete(@Nullable Throwable error, boolean cancelUpstream) {
    clearUpstream(cancelUpstream);
    if (!completed) {
      completed = true;
      if (error != null) {
        downstream.onError(error);
      } else {
        downstream.onComplete();
      }
    }
  }
}
