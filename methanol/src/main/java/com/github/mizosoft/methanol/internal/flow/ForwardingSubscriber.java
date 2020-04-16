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

/** A defensive {@code Subscriber<T>} forwarding to a downstream {@code Subscriber<? super T>}. */
public abstract class ForwardingSubscriber<T> implements Subscriber<T> {

  protected final Upstream upstream;
  private boolean completed;

  protected ForwardingSubscriber() {
    upstream = new Upstream();
  }

  /** Returns the downstream to which signals are forwarded. */
  protected abstract Subscriber<? super T> downstream();

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      try {
        downstream().onSubscribe(subscription);
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
        downstream().onNext(item);
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
        downstream().onError(error);
      } else {
        downstream().onComplete();
      }
    }
  }
}
