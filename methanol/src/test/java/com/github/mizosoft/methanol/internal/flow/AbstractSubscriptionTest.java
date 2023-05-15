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
import java.util.stream.IntStream;
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
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    assertThat(subscriber.awaitSubscription()).isSameAs(subscription);
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.errorCount()).isZero();
    assertThat(subscriber.completionCount()).isZero();
  }

  @ExecutorParameterizedTest
  void noItems(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.errorCount()).isZero();
    assertThat(subscriber.completionCount()).isOne();
  }

  @ExecutorParameterizedTest
  void errorSignal(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAliveOnError(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.completionCount()).isZero();
    assertThat(subscriber.errorCount()).isOne();
  }

  @ExecutorParameterizedTest
  void errorOnSubscribe(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().throwOnSubscribeAndOnNext(true);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.nextCount()).isZero();
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscriber.completionCount()).isZero();
  }

  @ExecutorParameterizedTest
  void oneItem(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    assertThat(subscriber.pollNext()).isOne();
    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isOne();
    assertThat(subscriber.completionCount()).isOne();
    assertThat(subscriber.errorCount()).isZero();
  }

  @ExecutorParameterizedTest
  void oneItemThenErrorNoWaitForItem(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    subscription.fireOrKeepAliveOnError(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.nextCount()).isLessThanOrEqualTo(1);
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscriber.completionCount()).isZero();
  }

  @ExecutorParameterizedTest
  void oneItemThenErrorWaitForItem(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    assertThat(subscriber.pollNext()).isOne();
    subscription.fireOrKeepAliveOnError(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.nextCount()).isEqualTo(1);
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscriber.completionCount()).isZero();
  }

  @ExecutorParameterizedTest
  void itemsAfterCancellation(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    for (int i = 1; i <= 40; ++i) {
      subscription.submit(i);
      if (i == 1) {
        subscription.cancel(); // Cancel after first onNext.
      }
    }
    subscription.complete();
    assertThat(subscriber.completionCount()).isZero();

    // No guarantee no items are received but not so many items should be received.
    assertThat(subscriber.nextCount()).isLessThanOrEqualTo(20);
  }

  @ExecutorParameterizedTest
  void errorOnNext(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    subscriber.throwOnNext(true);
    subscription.submit(2);
    subscription.complete();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscriber.completionCount()).isZero();

    // No guarantee whether throw happens in first or second onNext.
    assertThat(subscriber.nextCount()).isLessThanOrEqualTo(2);
  }

  @ExecutorParameterizedTest
  void itemOrder(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    for (int i = 1; i <= 20; ++i) {
      subscription.submit(i);
    }
    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isEqualTo(20);
    assertThat(subscriber.pollNext(20))
        .containsExactly(IntStream.rangeClosed(1, 20).boxed().toArray(Integer[]::new));
    assertThat(subscriber.completionCount()).isOne();
  }

  @ExecutorParameterizedTest
  void noItemsWithoutRequests(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    subscription.submit(2);

    subscriber.awaitSubscription();
    assertThat(subscriber.nextCount()).isEqualTo(0);

    subscriber.requestItems(3);
    subscription.submit(3);
    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.pollNext(3)).containsExactly(1, 2, 3);
    assertThat(subscriber.nextCount()).isEqualTo(3);
    assertThat(subscriber.completionCount()).isOne();
  }

  @ExecutorParameterizedTest
  void noItemsMoreThanRequestedAreReceived(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    subscription.submit(2);
    subscription.submit(3);

    subscriber.requestItems(1);
    assertThat(subscriber.pollNext()).isEqualTo(1);
    assertThat(subscriber.nextCount()).isEqualTo(1);

    subscriber.requestItems(2);
    assertThat(subscriber.pollNext(2)).containsExactly(2, 3);
    assertThat(subscriber.nextCount()).isEqualTo(3);

    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.nextCount()).isEqualTo(3);
    assertThat(subscriber.completionCount()).isOne();
  }

  @ExecutorParameterizedTest
  void requestMoreThanAvailable(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscriber.requestItems(2);
    assertThat(subscriber.nextCount()).isZero();

    subscription.submit(1);
    assertThat(subscriber.pollNext()).isOne();
    assertThat(subscriber.nextCount()).isOne();

    subscriber.requestItems(Long.MAX_VALUE);
    subscriber.requestItems(Long.MAX_VALUE);
    assertThat(subscription.currentDemand()).isPositive();

    subscription.submit(2);
    subscription.submit(3);
    subscription.complete();
    subscriber.awaitCompletion();
    assertThat(subscriber.pollNext(2)).containsExactly(2, 3);
  }

  @ExecutorParameterizedTest
  void requestOneReceiveOne(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscriber.awaitSubscription(); // Makes sure one item is automatically requested.
    subscriber.autoRequest(0);
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    assertThat(subscriber.pollNext()).isOne();
    assertThat(subscriber.nextCount()).isOne();
  }

  @ExecutorParameterizedTest
  void zeroRequest(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscriber.awaitSubscription();
    subscription.request(0);
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    assertThat(subscriber.awaitError()).isInstanceOf(IllegalArgumentException.class);
    assertThat(subscriber.errorCount()).isOne();
  }

  @ExecutorParameterizedTest
  void negativeRequest(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscriber.requestItems(-1);
    subscription.submit(1);
    subscription.submit(2);
    subscription.complete();
    assertThat(subscriber.awaitError()).isInstanceOf(IllegalArgumentException.class);
    assertThat(subscriber.errorCount()).isOne();
  }

  /** Tests scenario for JDK-8187947: A race condition in SubmissionPublisher. */
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
        if (++received == N) finished.countDown();
        else CompletableFuture.runAsync(() -> ref.get().submit(Boolean.TRUE));
      }

      public void onError(Throwable t) {
        throw new AssertionError(t);
      }

      public void onComplete() {}
    }
    var subscription = new SubmittableSubscription<>(new Sub(), executor);
    ref.set(subscription);
    subscription.fireOrKeepAlive();
    CompletableFuture.runAsync(() -> subscription.submit(Boolean.TRUE)).get(20, TimeUnit.SECONDS);
    assertThat(finished.await(20, TimeUnit.SECONDS)).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByCancellation(Executor executor) {
    var downstream = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(downstream, executor);
    for (int i = 0; i < 5; i++) {
      subscription.cancel();
    }
    subscription.awaitAbort();
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByCompletion(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.submit(1);
    subscription.complete();
    subscriber.awaitCompletion(); // Complete first.
    subscription.cancel(); // Mustn't call abort() again.
    subscription.awaitAbort();
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isFalse();
  }

  @ExecutorParameterizedTest
  void abortByError(Executor executor) {
    var subscriber = new TestSubscriber<Integer>();
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.submit(1);
    subscription.fireOrKeepAliveOnError(new TestException());
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class); // Complete first.
    subscription.cancel(); // Mustn't call abort() again.
    subscription.awaitAbort();
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isFalse();
  }

  @ExecutorParameterizedTest
  void abortByErrorOnSubscribe(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().throwOnSubscribe(true);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    subscription.awaitAbort();
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  @ExecutorParameterizedTest
  void abortByErrorOnNext(Executor executor) {
    var subscriber = new TestSubscriber<Integer>().throwOnNext(true);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();
    subscription.submit(1);
    subscription.complete();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);
    subscription.awaitAbort();
    assertThat(subscriber.errorCount()).isOne();
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  @ExecutorParameterizedTest
  void rejectedExecution() {
    Executor busyExecutor =
        r -> {
          throw new RejectedExecutionException();
        };
    var subscription = new SubmittableSubscription<>(new TestSubscriber<Integer>(), busyExecutor);
    assertThatExceptionOfType(RejectedExecutionException.class)
        .isThrownBy(subscription::fireOrKeepAlive);
    assertThat(subscription.abortCount()).isOne();
    assertThat(subscription.flowInterrupted()).isTrue();
  }

  /** Test that emit() stops in case of an asynchronous signalError detected by submitOnNext. */
  @ExecutorParameterizedTest
  void pendingErrorStopsSubmission(Executor executor) {
    var onErrorLatch = new CountDownLatch(1);
    var firstOnNextLatch = new CountDownLatch(1);
    var subscriber =
        new TestSubscriber<Integer>() {
          @Override
          public void onNext(Integer item) {
            firstOnNextLatch.countDown();
            awaitUninterruptibly(onErrorLatch);
            super.onNext(item);
          }
        }.autoRequest(0);
    var subscription = new SubmittableSubscription<>(subscriber, executor);
    subscription.fireOrKeepAlive();

    // Make 2 items available without signalling.
    subscription.submitSilently(1);
    subscription.submitSilently(1);

    // Request 2 items (request asynchronously to not block in case the executor is synchronous).
    CompletableFuture.runAsync(() -> subscriber.requestItems(2L));

    // Wait till first onNext comes.
    awaitUninterruptibly(firstOnNextLatch);

    // Set pendingError (first onNext now blocking).
    subscription.fireOrKeepAliveOnError(new TestException());

    // Let first onNext pass.
    onErrorLatch.countDown();
    assertThat(subscriber.awaitError()).isInstanceOf(TestException.class);

    // Second onNext isn't called!
    assertThat(subscriber.nextCount()).isOne();
  }
}
