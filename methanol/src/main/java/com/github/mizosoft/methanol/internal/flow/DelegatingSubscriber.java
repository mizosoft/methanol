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

package com.github.mizosoft.methanol.internal.flow;

import static java.util.Objects.requireNonNull;

import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import org.checkerframework.checker.nullness.qual.Nullable;

/** A defensive wrapper over a {@code Subscriber<T>}. */
public class DelegatingSubscriber<T, S extends Subscriber<? super T>> implements Subscriber<T> {

  protected final S downstream;
  protected final Upstream upstream;
  // Safety flag to ensure that deferred cancellation from upstream doesn't
  // cause the downstream to receive post-completion signals in case downstream's
  // onSubscribe or onNext threw previously
  private volatile boolean completed;

  protected DelegatingSubscriber(S downstream) {
    this.downstream = requireNonNull(downstream, "downstream");
    upstream = new Upstream();
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      try {
        downstream.onSubscribe(subscription);
      } catch (Throwable t) {
        upstream.cancel();
        complete(t);
      }
    }
  }

  @Override
  public void onNext(T item) {
    requireNonNull(item);
    if (!completed) {
      try {
        downstream.onNext(item);
      } catch (Throwable t) {
        upstream.cancel();
        complete(t);
      }
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    complete(throwable);
  }

  @Override
  public void onComplete() {
    upstream.clear();
    complete(null);
  }

  private void complete(@Nullable Throwable error) {
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
