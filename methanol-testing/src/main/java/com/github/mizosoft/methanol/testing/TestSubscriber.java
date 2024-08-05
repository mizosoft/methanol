/*
 * Copyright (c) 2024 Moataz Abdelnasser
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

import static com.github.mizosoft.methanol.internal.Validate.castNonNull;
import static java.util.Objects.requireNonNull;
import static org.assertj.core.api.Assertions.assertThat;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;

/**
 * A {@link Subscriber} implementation that facilitates testing {@link Publisher} implementations
 * and the like.
 */
public class TestSubscriber<T> implements Subscriber<T> {
  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS);

  private final Lock lock = new ReentrantLock();
  private final Condition subscriptionReceived = lock.newCondition();
  private final Condition itemsAvailable = lock.newCondition();
  private final Condition completion = lock.newCondition();

  @GuardedBy("lock")
  private int nextCount;

  @GuardedBy("lock")
  private int errorCount;

  @GuardedBy("lock")
  private int completionCount;

  @GuardedBy("lock")
  private long autoRequest = 1;

  @GuardedBy("lock")
  private @MonotonicNonNull Subscription subscription;

  @GuardedBy("lock")
  private @MonotonicNonNull Throwable lastError;

  @GuardedBy("lock")
  private final Deque<T> items = new ArrayDeque<>();

  @GuardedBy("lock")
  private boolean throwOnSubscribe = false;

  @GuardedBy("lock")
  private boolean throwOnNext = false;

  public TestSubscriber() {}

  public int nextCount() {
    lock.lock();
    try {
      return nextCount;
    } finally {
      lock.unlock();
    }
  }

  public int completionCount() {
    lock.lock();
    try {
      return completionCount;
    } finally {
      lock.unlock();
    }
  }

  public int errorCount() {
    lock.lock();
    try {
      return errorCount;
    } finally {
      lock.unlock();
    }
  }

  @CanIgnoreReturnValue
  public TestSubscriber<T> throwOnSubscribeAndOnNext(boolean on) {
    lock.lock();
    try {
      throwOnSubscribe = on;
      throwOnNext = on;
      return this;
    } finally {
      lock.unlock();
    }
  }

  @CanIgnoreReturnValue
  public TestSubscriber<T> throwOnSubscribe(boolean on) {
    lock.lock();
    try {
      throwOnSubscribe = on;
      return this;
    } finally {
      lock.unlock();
    }
  }

  @CanIgnoreReturnValue
  public TestSubscriber<T> throwOnNext(boolean on) {
    lock.lock();
    try {
      throwOnNext = on;
      return this;
    } finally {
      lock.unlock();
    }
  }

  @CanIgnoreReturnValue
  public TestSubscriber<T> autoRequest(long n) {
    lock.lock();
    try {
      autoRequest = n;
      return this;
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onSubscribe(Subscription subscription) {
    requireNonNull(subscription);
    lock.lock();
    try {
      this.subscription = subscription;
      subscriptionReceived.signalAll();
      if (autoRequest > 0) {
        subscription.request(autoRequest);
      }
      if (throwOnSubscribe) {
        throw new TestException();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onNext(T item) {
    requireNonNull(item);
    lock.lock();
    try {
      nextCount++;
      items.addLast(item);
      itemsAvailable.signalAll();
      if (autoRequest > 0) {
        subscription.request(autoRequest);
      }
      if (throwOnNext) {
        throw new TestException();
      }
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onError(Throwable throwable) {
    requireNonNull(throwable);
    lock.lock();
    try {
      errorCount++;
      lastError = throwable;
      completion.signalAll();
    } finally {
      lock.unlock();
    }
  }

  @Override
  public void onComplete() {
    lock.lock();
    try {
      completionCount++;
      completion.signalAll();
    } finally {
      lock.unlock();
    }
  }

  public Subscription awaitSubscription() {
    return awaitSubscription(DEFAULT_TIMEOUT);
  }

  public Subscription awaitSubscription(Duration timeout) {
    lock.lock();
    try {
      @SuppressWarnings("GuardedBy")
      BooleanSupplier hasSubscription = () -> subscription != null;
      assertThat(await(subscriptionReceived, hasSubscription, timeout))
          .withFailMessage("expected onSubscribe within " + timeout)
          .isTrue();
      return subscription;
    } finally {
      lock.unlock();
    }
  }

  public void requestItems(long n) {
    awaitSubscription().request(n);
  }

  public List<T> peekAvailable() {
    lock.lock();
    try {
      return List.copyOf(items);
    } finally {
      lock.unlock();
    }
  }

  public List<T> pollAll() {
    lock.lock();
    try {
      awaitCompletion();
      var remainingItems = new ArrayList<T>();
      while (!items.isEmpty()) {
        remainingItems.add(items.remove());
      }
      return List.copyOf(remainingItems);
    } finally {
      lock.unlock();
    }
  }

  public T pollNext() {
    return pollNext(1).get(0);
  }

  public List<T> pollNext(int n) {
    return pollNext(n, DEFAULT_TIMEOUT);
  }

  public List<T> pollNext(int n, Duration timeout) {
    lock.lock();
    try {
      @SuppressWarnings("GuardedBy")
      BooleanSupplier hasEnoughItems = () -> items.size() >= n;
      assertThat(await(itemsAvailable, hasEnoughItems, timeout))
          .withFailMessage("expected onNext within " + timeout)
          .isTrue();
      var polled = new ArrayList<T>();
      for (int i = 0; i < n; i++) {
        polled.add(items.remove());
      }
      return List.copyOf(polled);
    } finally {
      lock.unlock();
    }
  }

  public void awaitCompletion() {
    awaitCompletion(DEFAULT_TIMEOUT);
  }

  public void awaitCompletion(Duration timeout) {
    lock.lock();
    try {
      @SuppressWarnings("GuardedBy")
      BooleanSupplier isComplete = () -> completionCount > 0 || errorCount > 0;
      assertThat(await(completion, isComplete, timeout))
          .withFailMessage("expected completion within " + timeout)
          .isTrue();
    } finally {
      lock.unlock();
    }
  }

  public Throwable awaitError() {
    return awaitError(DEFAULT_TIMEOUT);
  }

  public Throwable awaitError(Duration timeout) {
    lock.lock();
    try {
      @SuppressWarnings("GuardedBy")
      BooleanSupplier isComplete = () -> completionCount > 0 || errorCount > 0;
      assertThat(await(completion, isComplete, timeout))
          .withFailMessage("expected an error within " + timeout)
          .isTrue();
      assertThat(lastError).withFailMessage("expected onError but got onComplete").isNotNull();
      return castNonNull(lastError);
    } finally {
      lock.unlock();
    }
  }

  @GuardedBy("lock")
  private boolean await(Condition condition, BooleanSupplier awaitUntil, Duration timeout) {
    long remainingNanos = TimeUnit.NANOSECONDS.convert(timeout);
    while (!awaitUntil.getAsBoolean()) {
      try {
        if (remainingNanos <= 0) {
          return false;
        }
        remainingNanos = condition.awaitNanos(remainingNanos);
      } catch (InterruptedException ex) {
        throw new RuntimeException(ex);
      }
    }
    return true;
  }
}
