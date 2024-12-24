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

package com.github.mizosoft.methanol.testing;

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.function.Supplier;

/** A publisher that fails immediately with a user-supplied error. */
public final class FailingPublisher<T> implements Publisher<T> {
  private final Supplier<Throwable> errorSupplier;

  public FailingPublisher(Supplier<Throwable> errorSupplier) {
    this.errorSupplier = errorSupplier;
  }

  @Override
  public void subscribe(Subscriber<? super T> subscriber) {
    requireNonNull(subscriber);
    var error = errorSupplier.get();
    try {
      subscriber.onSubscribe(FlowSupport.NOOP_SUBSCRIPTION);
    } catch (Throwable t) {
      error.addSuppressed(t);
    } finally {
      subscriber.onError(error);
    }
  }
}
