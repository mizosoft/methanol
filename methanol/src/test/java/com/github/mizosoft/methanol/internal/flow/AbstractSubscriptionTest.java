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

/*
 * A lot of code in these tests is extracted from JDK's SubmissionPublisherTest from the JSR166 TCK.
 * The file had the following license header:
 *
 * This file is available under and governed by the GNU General Public
 * License version 2 only, as published by the Free Software Foundation.
 * However, the following notice accompanied the original version of this
 * file:
 *
 * Written by Doug Lea and Martin Buchholz with assistance from
 * members of JCP JSR-166 Expert Group and released to the public
 * domain, as explained at
 * http://creativecommons.org/publicdomain/zero/1.0/
 */

package com.github.mizosoft.methanol.internal.flow;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatExceptionOfType;
import static org.junit.jupiter.api.Assertions.assertNotNull;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testing.Logging;
import com.github.mizosoft.methanol.testing.SubmittableSubscription;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.TestSubscriber;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.Flow.Subscriber;
import java.util.concurrent.Flow.Subscription;
import java.util.concurrent.ForkJoinPool;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Timeout;
import org.junit.jupiter.api.extension.ExtendWith;

@Timeout(20)
@ExtendWith(ExecutorExtension.class)
class AbstractSubscriptionTest {
  static {
    Logging.disable(AbstractSubscription.class);
  }

  @ExecutorParameterizedTest
  void subscribeNoSignals(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    assertNotNull(subscriber.subscription);
    assertThat(subscriber.subscription).isNotNull();
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.errorCount).isZero();
    assertThat(subscriber.completionCount).isZero();
  }

  @ExecutorParameterizedTest
  void noItems(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscription.complete();
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.errorCount).isZero();
    assertThat(subscriber.completionCount).isOne();
  }

  @ExecutorParameterizedTest
  void errorSignal(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signalError(new TestException());
    subscriber.awaitError();
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.completionCount).isZero();
    assertThat(subscriber.errorCount).isOne();
  }

  @ExecutorParameterizedTest
  void errorOnSubscribe(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    subscriber.throwOnCall = true;
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitError();
    assertThat(subscriber.nextCount).isZero();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.completionCount).isZero();
  }

  @ExecutorParameterizedTest
  void oneItem(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscription.submit(1);
    subscription.complete();
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isOne();
    assertThat(subscriber.completionCount).isOne();
    assertThat(subscriber.errorCount).isZero();
  }

  @ExecutorParameterizedTest
  void oneItemThenError(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscription.submit(1);
    subscription.signalError(new TestException());
    subscriber.awaitOnSubscribe();
    subscriber.awaitError();
    assertThat(subscriber.nextCount).isLessThanOrEqualTo(1);
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.completionCount).isZero();
  }

  @ExecutorParameterizedTest
  void itemsAfterCancellation(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscription.submit(1);
    subscriber.subscription.cancel();
    for (int i = 2; i <= 20; ++i) {
      subscription.submit(i);
    }
    subscription.complete();
    assertThat(subscriber.nextCount).isLessThan(20);
    assertThat(subscriber.completionCount).isZero();
  }

  @ExecutorParameterizedTest
  void errorOnNext(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscription.submit(1);
    subscriber.throwOnCall = true;
    subscription.submit(2);
    subscription.complete();
    subscriber.awaitComplete();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.completionCount).isZero();
  }

  @ExecutorParameterizedTest
  void itemOrder(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    for (int i = 1; i <= 20; ++i) {
      subscription.submit(i);
    }
    subscription.complete();
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isEqualTo(20);
    assertThat(subscriber.items.peekLast()).isEqualTo(20);
    assertThat(subscriber.completionCount).isOne();
  }

  @ExecutorParameterizedTest
  void noItemsWithoutRequests(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    subscriber.request = 0L;
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscription.submit(1);
    subscription.submit(2);
    assertThat(subscriber.nextCount).isEqualTo(0);
    subscriber.subscription.request(3);
    subscription.submit(3);
    subscription.complete();
    subscriber.awaitComplete();
    assertThat(subscriber.nextCount).isEqualTo(3);
    assertThat(subscriber.completionCount).isOne();
  }

  @ExecutorParameterizedTest
  void requestOneReceiveOne(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe(); // Requests 1 item
    subscriber.request = 0L;
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    subscriber.awaitOnNext(1);
    assertThat(subscriber.nextCount).isOne();
  }

  @ExecutorParameterizedTest
  void zeroRequest(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscriber.subscription.request(0);
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    subscriber.awaitError();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.lastError).isInstanceOf(IllegalArgumentException.class);
  }

  @ExecutorParameterizedTest
  void negativeRequest(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscriber.subscription.request(-1L);
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    subscriber.awaitError();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.lastError).isInstanceOf(IllegalArgumentException.class);
  }

  /**
   * Tests scenario for
   * JDK-8187947: A race condition in SubmissionPublisher
   */
  @ExecutorParameterizedTest
  void testMissedSignal_8187947(Executor executor) throws Exception {
    int N =
        ((ForkJoinPool.getCommonPoolParallelism() < 2) // JDK-8212899
            ? (1 << 5)
            : (1 << 10))
            * ((1 << 6));
    var finished = new CountDownLatch(1);
    var ref = new AtomicReference<SubmittableSubscription<Boolean>>();
    class Sub implements Subscriber<Boolean> {
      int received;
      public void onSubscribe(Subscription s) {
        s.request(N);
      }
      public void onNext(Boolean item) {
        if (++received == N)
          finished.countDown();
        else
          CompletableFuture.runAsync(() -> ref.get().submit(Boolean.TRUE));
      }
      public void onError(Throwable t) { throw new AssertionError(t); }
      public void onComplete() {}
    }
    var subscription = new SubmittableSubscription<>(new Sub(), executor);
    ref.set(subscription);
    subscription.signal(true);
    CompletableFuture.runAsync(() -> subscription.submit(Boolean.TRUE)).get(20, TimeUnit.SECONDS);
    assertThat(finished.await(20, TimeUnit.SECONDS)).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByCancellation(Executor executor) {
    var subscription = subscription(new TestSubscriber<>(), executor);
    subscription.cancel();
    subscription.cancel();
    subscription.awaitAbort();
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByCompletion(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.submit(1);
    subscription.complete();
    subscriber.awaitComplete();
    subscriber.subscription.cancel();
    subscription.awaitAbort();
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isFalse();
  }

  @ExecutorParameterizedTest
  void abortByError(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.submit(1);
    subscription.signalError(new TestException());
    subscriber.awaitError();
    subscription.awaitAbort();
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isFalse();
  }

  @ExecutorParameterizedTest
  void abortByErrorOnSubscribe(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    subscriber.throwOnCall = true;
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitError();
    subscription.awaitAbort();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByErrorOnNext(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = subscription(subscriber, executor);
    subscription.signal(true);
    subscriber.awaitOnSubscribe();
    subscriber.throwOnCall = true;
    subscription.submit(1);
    subscription.complete();
    subscriber.awaitError();
    subscription.awaitAbort();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void rejectedExecution() {
    Executor busyExecutor = r -> { throw new RejectedExecutionException(); };
    var subscription = new SubmittableSubscription<>(new TestSubscriber<Integer>(), busyExecutor);
    assertThatExceptionOfType(RejectedExecutionException.class)
        .isThrownBy(() -> subscription.signal(true));
    assertThat(subscription.aborts).isOne();
    assertThat(subscription.flowInterrupted).isTrue();
  }

  @ExecutorParameterizedTest
  void multipleErrors(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    subscriber.throwOnCall = true;
    var subscription = subscription(subscriber, executor);
    var error = new TestException();
    subscription.signalError(error);
    subscriber.awaitError();
    assertThat(subscriber.errorCount).isOne();
    assertThat(subscriber.lastError).hasSuppressedException(error);
  }

  /** Test that emit() stops in case of an asynchronous signalError detected by submitOnNext. */
  @ExecutorParameterizedTest
  void pendingErrorStopsSubmission(Executor executor) {
    var onErrorLatch = new CountDownLatch(1);
    var firstOnNextLatch = new CountDownLatch(1);
    var subscriber = new TestSubscriber<Integer>() {
      @Override
      public void onNext(Integer item) {
        firstOnNextLatch.countDown();
        awaitUninterruptibly(onErrorLatch);
        super.onNext(item);
      }
    };
    var subscription = subscription(subscriber, executor);
    subscriber.request = 0L;
    subscription.signal(true);

    // Make 2 items available
    subscription.items.offer(1);
    subscription.items.offer(1);

    // Request 2 items (request asynchronously to not block in case the executor is synchronous)
    subscriber.awaitOnSubscribe();
    CompletableFuture.runAsync(() -> subscriber.subscription.request(2L));

    // Wait till first onNext comes
    awaitUninterruptibly(firstOnNextLatch);

    // Set pendingError (first onNext now blocking)
    subscription.signalError(new TestException());

    // Let first onNext pass
    onErrorLatch.countDown();
    subscriber.awaitError();

    // Second onNext shouldn't be called!
    assertThat(subscriber.nextCount).isOne();
  }

  private SubmittableSubscription<Integer> subscription(
      Subscriber<? super Integer> downstream, Executor executor) {
    return new SubmittableSubscription<>(downstream, executor);
  }
}
