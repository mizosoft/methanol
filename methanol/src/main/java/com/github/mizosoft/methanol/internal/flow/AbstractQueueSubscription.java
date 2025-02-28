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

import java.util.Queue;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A subscription that emits items from a queue and completes downstream as soon as a sentinel value
 * is observed.
 */
public class AbstractQueueSubscription<T> extends AbstractPollableSubscription<T> {
  private static final Object DEFAULT_SENTINEL = new Object();

  private final Queue<T> queue;

  /** A value that indicates no more items are to be expected. */
  private final T sentinel;

  private boolean complete;

  protected AbstractQueueSubscription(Subscriber<? super T> downstream, Executor executor) {
    this(downstream, executor, new ConcurrentLinkedQueue<>());
  }

  @SuppressWarnings("unchecked")
  protected AbstractQueueSubscription(
      Subscriber<? super T> downstream, Executor executor, Queue<T> queue) {
    this(downstream, executor, queue, (T) DEFAULT_SENTINEL); // A benign abuse of Java's generics.
  }

  protected AbstractQueueSubscription(
      Subscriber<? super T> downstream, Executor executor, Queue<T> queue, T sentinel) {
    super(downstream, executor);
    this.queue = requireNonNull(queue);
    this.sentinel = requireNonNull(sentinel);
  }

  protected void submit(T item) {
    queue.add(item);
    fireOrKeepAliveOnNext();
  }

  protected void submitSilently(T item) {
    queue.add(item);
  }

  protected void submitAndComplete(T lastItem) {
    queue.add(lastItem);
    queue.add(sentinel);
    fireOrKeepAlive();
  }

  @Override
  protected @Nullable T poll() {
    var next = queue.poll();
    complete |= next == sentinel;
    return complete ? null : next;
  }

  @Override
  protected boolean isComplete() {
    return complete || (complete = (queue.peek() == sentinel));
  }

  @Override
  protected void abort(boolean flowInterrupted) {
    queue.clear();
  }

  protected void complete() {
    queue.add(sentinel);
    fireOrKeepAlive();
  }
}
