/*
 * Copyright (c) 2025 Moataz Hussein
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
import static org.assertj.core.api.Assertions.assertThat;

import com.google.errorprone.annotations.CanIgnoreReturnValue;
import com.google.errorprone.annotations.concurrent.GuardedBy;
import java.io.PrintWriter;
import java.io.StringWriter;
import java.lang.invoke.MethodHandles;
import java.lang.invoke.VarHandle;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.concurrent.CompletionException;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Flow.Publisher;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.Lock;
import java.util.concurrent.locks.ReentrantLock;
import java.util.function.BooleanSupplier;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.checkerframework.checker.nullness.qual.Nullable;

/**
 * A {@link Subscriber} implementation that facilitates testing {@link Publisher} implementations
 * and the like.
 */
public class TestSubscriber<T> implements Subscriber<T> {
  private static final VarHandle LATCH;

  static {
    try {
      LATCH = MethodHandles.lookup().findVarHandle(TestSubscriber.class, "latch", LatchOwner.class);
    } catch (NoSuchFieldException | IllegalAccessException e) {
      throw new ExceptionInInitializerError(e);
    }
  }

  private static final Duration DEFAULT_TIMEOUT = Duration.ofSeconds(TestUtils.TIMEOUT_SECONDS);

  private final Lock lock = new ReentrantLock();
  private final Condition subscriptionReceived = lock.newCondition();
  private final Condition itemsAvailable = lock.newCondition();
  private final Condition completion = lock.newCondition();
  private final List<AssertionError> protocolViolations = new CopyOnWriteArrayList<>();
  private @Nullable LatchOwner latch;

  private static final class LatchOwner {
    final Thread thread;
    final String label;
    final @Nullable LatchOwner next;

    LatchOwner(Thread thread, String label, @Nullable LatchOwner next) {
      this.thread = thread;
      this.label = label;
      this.next = next;
    }

    LatchOwner push(String label) {
      return new LatchOwner(thread, label, this);
    }

    @Nullable LatchOwner pop() {
      return next;
    }

    @Override
    public String toString() {
      return label + " by " + thread;
    }
  }

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

  private final AtomicLong requested = new AtomicLong();

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

  private void addRequest(long n) {
    while (true) {
      long current = requested.get();
      long updated = current + n;
      if (updated < 0) {
        updated = Long.MAX_VALUE;
      }
      if (requested.compareAndSet(current, updated)) {
        return;
      }
    }
  }

  @Override
  @SuppressWarnings("GuardedBy")
  public void onSubscribe(Subscription subscription) {
    if (!check(() -> assertThat(subscription).isNotNull())) {
      return;
    }

    boolean latchAcquired = check(() -> acquireLatch("onSubscribe(" + subscription + ")"));
    try {
      lock.lock();
      try {
        boolean calledOnce =
            check(
                () ->
                    assertThat(this.subscription)
                        .withFailMessage(
                            "Receiving "
                                + subscription
                                + " despite already having received "
                                + this.subscription)
                        .isNull());
        if (!calledOnce) {
          subscription.cancel();
          return;
        }

        this.subscription = subscription;
        subscriptionReceived.signalAll();
        if (autoRequest > 0) {
          addRequest(autoRequest);
          subscription.request(autoRequest);
        }
        if (throwOnSubscribe) {
          throw new TestException();
        }
      } finally {
        lock.unlock();
      }
    } finally {
      if (latchAcquired) {
        releaseLatch();
      }
    }
  }

  @Override
  @SuppressWarnings("GuardedBy")
  public void onNext(T item) {
    if (!check(() -> assertThat(item).isNotNull())) {
      return;
    }

    var label = "onNext(" + item + ")";
    boolean latchAcquired = check(() -> acquireLatch(label));
    try {
      lock.lock();
      try {
        check(() -> assertNotEndOfStream(label));
        check(() -> assertReceivedSubscription(label));

        nextCount++;
        long localRequested = requested.get();
        check(
            () ->
                assertThat((long) nextCount)
                    .withFailMessage(
                        () ->
                            "Receiving more items than requested; received="
                                + nextCount
                                + ", requested="
                                + localRequested)
                    .isLessThanOrEqualTo(localRequested));

        items.addLast(item);
        itemsAvailable.signalAll();
        if (autoRequest > 0 && subscription != null) {
          addRequest(autoRequest);
          subscription.request(autoRequest);
        }
        if (throwOnNext) {
          throw new TestException();
        }
      } finally {
        lock.unlock();
      }
    } finally {
      if (latchAcquired) {
        releaseLatch();
      }
    }
  }

  @Override
  @SuppressWarnings("GuardedBy")
  public void onError(Throwable throwable) {
    if (!check(() -> assertThat(throwable).isNotNull())) {
      return;
    }

    var label = "onError(" + exceptionToString(throwable) + ")";
    boolean isLatchAcquired = check(() -> acquireLatch(label));
    try {
      lock.lock();
      try {
        check(() -> assertNotEndOfStream(label));
        check(() -> assertReceivedSubscription(label));
        errorCount++;
        lastError = throwable;
        completion.signalAll();
        itemsAvailable.signalAll(); // Wake up potential waiters on onNext.
      } finally {
        lock.unlock();
      }
    } finally {
      if (isLatchAcquired) {
        releaseLatch();
      }
    }
  }

  @Override
  @SuppressWarnings("GuardedBy")
  public void onComplete() {
    acquireLatch("onComplete()");
    try {
      lock.lock();
      try {
        check(() -> assertNotEndOfStream("onComplete()"));
        check(() -> assertReceivedSubscription("onComplete()"));
        completionCount++;
        completion.signalAll();
        itemsAvailable.signalAll(); // Wake up potential waiters on onNext.
      } finally {
        lock.unlock();
      }
    } finally {
      releaseLatch();
    }
  }

  @GuardedBy("lock")
  @SuppressWarnings("GuardedBy")
  private void assertNotEndOfStream(String label) {
    assertThat(completionCount + errorCount)
        .withFailMessage(
            () ->
                "Calling "
                    + label
                    + " after stream has ended; got "
                    + completionCount
                    + " onComplete() and "
                    + errorCount
                    + " onError(Throwable)"
                    + (lastError != null
                        ? " with lastError=<" + exceptionToString(lastError) + ">"
                        : ""))
        .isZero();
  }

  @GuardedBy("lock")
  private void assertReceivedSubscription(String label) {
    assertThat(subscription)
        .withFailMessage(() -> "Calling " + label + " before receiving a subscription")
        .isNotNull();
  }

  private boolean check(Runnable assertion) {
    try {
      assertion.run();
      return true;
    } catch (AssertionError e) {
      protocolViolations.add(e);
      return false;
    }
  }

  private void acquireLatch(String label) {
    var currentThread = Thread.currentThread();
    var currentOwner = latch;
    var nextOwner =
        currentOwner != null && currentOwner.thread == currentThread
            ? currentOwner.push(label) // Recursive call.
            : new LatchOwner(currentThread, label, null);
    var currentOwnerBeforeCas = currentOwner;
    if ((currentOwner != null && nextOwner.thread != currentOwner.thread)
        || (currentOwner = (LatchOwner) LATCH.compareAndExchange(this, currentOwner, nextOwner))
            != currentOwnerBeforeCas) {
      throw new AssertionError(
          "Concurrent subscriber calls (owner=<"
              + currentOwner
              + ">, acquirer=<"
              + nextOwner
              + ">)");
    }
  }

  @SuppressWarnings("NullAway")
  private void releaseLatch() {
    var currentThread = Thread.currentThread();
    var currentOwner = latch;
    assertThat(currentOwner)
        .withFailMessage("Latch not owned by current thread")
        .isNotNull()
        .matches(owner -> owner.thread == currentThread);

    // We know for sure that there can be no contention here. Contention with another releaseLatch
    // is impossible as the assertion above guarantees the releaser only comes from the owner's
    // thread. Contention with acquireLatch is also impossible, as it only CASes if the owner is
    // null or the acquirer has the owner's thread. Absence of the first condition is guaranteed
    // by the assertion above. Absence of the second is guaranteed by the fact that a thread cannot
    // be an acquirer and releaser at the same time.
    latch = currentOwner.pop();
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
          .withFailMessage("Expected onSubscribe within " + timeout)
          .isTrue();

      var localSubscription = castNonNull(subscription);
      return new Subscription() {
        @Override
        public void request(long n) {
          addRequest(n);
          localSubscription.request(n);
        }

        @Override
        public void cancel() {
          localSubscription.cancel();
        }
      };
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

  @SuppressWarnings("GuardedBy")
  public List<T> pollNext(int n, Duration timeout) {
    lock.lock();
    try {
      assertThat(
              await(
                  itemsAvailable,
                  () -> completionCount > 0 || errorCount > 0 || items.size() >= n,
                  timeout))
          .withFailMessage(() -> "Expected onNext or completion within " + timeout)
          .isTrue();
      assertThat(items.size() >= n)
          .withFailMessage(
              () ->
                  String.format(
                      "Expected onNext %d times but got %d then %s",
                      n,
                      items.size(),
                      lastError != null
                          ? "onError(" + exceptionToString(lastError) + ")"
                          : "onComplete()"))
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
          .withFailMessage("Expected completion within " + timeout)
          .isTrue();

      var localLastError = lastError;
      assertThat(lastError)
          .withFailMessage(
              () ->
                  "Expected onComplete() but got onError("
                      + exceptionToString(castNonNull(localLastError))
                      + ")")
          .isNull();
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
          .withFailMessage("Expected an error within " + timeout)
          .isTrue();

      var localLastError = lastError;
      assertThat(lastError)
          .withFailMessage("Expected onError(Throwable) but got onComplete()")
          .isNotNull();
      return castNonNull(localLastError);
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
        throw new CompletionException(ex);
      }
    }
    return true;
  }

  public List<Throwable> protocolViolations() {
    return List.copyOf(protocolViolations);
  }

  private static String exceptionToString(Throwable ex) {
    var writer = new StringWriter();
    ex.printStackTrace(new PrintWriter(writer));
    return writer.toString();
  }
}
