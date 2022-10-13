/*
 * Copyright (c) 2022 Moataz Abdelnasser
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
import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;

/**
 * A {@code Flow.Publisher<T>} that emits submitted items. The publisher is similar to {@link
 * java.util.concurrent.SubmissionPublisher} but doesn't require the executor to operate
 * concurrently and has an unbounded buffer.
 */
public final class SubmittablePublisher<T> implements Publisher<T>, AutoCloseable {
  private final List<SubmittableSubscription<T>> subscriptions = new CopyOnWriteArrayList<>();
  private final Executor executor;

  public SubmittablePublisher(Executor executor) {
    this.executor = requireNonNull(executor);
  }

  @Override
  public void subscribe(Subscriber<? super T> subscriber) {
    var subscription = new SubmittableSubscription<T>(subscriber, executor);
    subscriptions.add(subscription);
    subscription.signal(true); // Apply onSubscribe.
  }

  public SubmittableSubscription<T> firstSubscription() {
    assertThat(subscriptions).withFailMessage("nothing has subscribed yet").isNotEmpty();
    return subscriptions.get(0);
  }

  public void submit(T item) {
    subscriptions.forEach(s -> s.submit(item));
  }

  public void submitAll(Iterable<T> items) {
    items.forEach(this::submit);
  }

  @Override
  public void close() {
    subscriptions.forEach(SubmittableSubscription::complete);
  }
}
