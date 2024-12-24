/*
 * Copyright (c) 2024 Moataz Hussein
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

import com.github.mizosoft.methanol.internal.Utils;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;

/** A {@code Subscriber<T>} that forwards to a downstream {@code Subscriber<? super T>}. */
public abstract class ForwardingSubscriber<T> implements Subscriber<T> {
  protected final Upstream upstream = new Upstream();

  protected ForwardingSubscriber() {}

  /** Returns the downstream to which signals are forwarded. */
  protected abstract Subscriber<? super T> delegate();

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    if (upstream.setOrCancel(subscription)) {
      delegate().onSubscribe(subscription);
    }
  }

  @Override
  public void onNext(T item) {
    requireNonNull(item);
    delegate().onNext(item);
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    upstream.clear();
    delegate().onError(throwable);
  }

  @Override
  public void onComplete() {
    upstream.clear();
    delegate().onComplete();
  }

  @Override
  public String toString() {
    return Utils.forwardingObjectToString(this, delegate());
  }
}
