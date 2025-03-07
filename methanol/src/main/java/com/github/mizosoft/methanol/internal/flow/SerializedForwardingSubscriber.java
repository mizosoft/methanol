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

import com.github.mizosoft.methanol.internal.concurrent.SerialExecutor;
import java.util.concurrent.Flow.Subscription;

/** A forwarding subscriber that ensures the delegate isn't called concurrently. */
public abstract class SerializedForwardingSubscriber<T> extends ForwardingSubscriber<T> {
  private final SerialExecutor serialExecutor = new SerialExecutor(FlowSupport.SYNC_EXECUTOR);
  private boolean done; // Visibility piggybacks on SerialExecutor's synchronization.

  protected SerializedForwardingSubscriber() {}

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    serialExecutor.execute(() -> super.onSubscribe(subscription));
  }

  @Override
  public void onNext(T item) {
    requireNonNull(item);
    serialExecutor.execute(
        () -> {
          if (!done) {
            super.onNext(item);
          }
        });
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    serialExecutor.execute(
        () -> {
          if (!done) {
            done = true;
            super.onError(throwable);
          }
        });
  }

  @Override
  public void onComplete() {
    serialExecutor.execute(
        () -> {
          if (!done) {
            done = true;
            super.onComplete();
          }
        });
  }
}
