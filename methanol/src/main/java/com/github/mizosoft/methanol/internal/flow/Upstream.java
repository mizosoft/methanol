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

package com.github.mizosoft.methanol.internal.flow;

import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.util.concurrent.Flow.Subscription;

/** A one-use atomic reference to an upstream subscription. */
public final class Upstream {
  private static final Subscription UNSET_SUBSCRIPTION =
      new Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
      };

  private static final Subscription CANCELLED_SUBSCRIPTION =
      new Subscription() {
        @Override
        public void request(long n) {}

        @Override
        public void cancel() {}
      };

  private static final VarHandle SUBSCRIPTION;

  static {
    try {
      SUBSCRIPTION =
          MethodHandles.lookup().findVarHandle(Upstream.class, "subscription", Subscription.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private volatile Subscription subscription = UNSET_SUBSCRIPTION;

  public Upstream() {}

  /** Returns {@code true} if the subscription was {@link #setOrCancel(Subscription) set}. */
  public boolean isSet() {
    return subscription != null;
  }

  public boolean isCancelled() {
    return subscription == CANCELLED_SUBSCRIPTION;
  }

  /** Sets incoming subscription, cancels it if already set. */
  public boolean setOrCancel(Subscription incoming) {
    if (!SUBSCRIPTION.compareAndSet(this, UNSET_SUBSCRIPTION, incoming)) {
      incoming.cancel();
      return false;
    }
    return true;
  }

  /** Requests {@code n} items from upstream if set. */
  public void request(long n) {
    var currentSubscription = subscription;
    if (currentSubscription != null) {
      currentSubscription.request(n);
    }
  }

  /** Cancels the upstream if set. */
  public void cancel() {
    var cancelledSubscription = (Subscription) SUBSCRIPTION.getAndSet(this, CANCELLED_SUBSCRIPTION);
    if (cancelledSubscription != null) {
      cancelledSubscription.cancel();
    }
  }

  /** Just loses the reference to upstream if cancellation it is not required. */
  public void clear() {
    subscription = CANCELLED_SUBSCRIPTION;
  }

  public void cancel(boolean flowInterrupted) {
    if (flowInterrupted) {
      cancel();
    } else {
      clear();
    }
  }

  public Subscription get() {
    return subscription;
  }
}
