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

import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.ExecutorProvider;
import com.github.mizosoft.methanol.ExecutorProvider.ExecutorConfig;
import com.github.mizosoft.methanol.ExecutorProvider.ExecutorParameterizedTest;
import com.github.mizosoft.methanol.testutils.TestException;
import com.github.mizosoft.methanol.testutils.TestSubscriber;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentLinkedQueue;
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
@ExtendWith(ExecutorProvider.class)
class AbstractSubscriptionTest {
  @ExecutorParameterizedTest
  @ExecutorConfig
  void subscribeNoSignals(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    assertNotNull(s.subscription);
    assertEquals(0, s.nexts);
    assertEquals(0, s.errors);
    assertEquals(0, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void noItems(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    p.complete();
    s.awaitComplete();
    assertEquals(0, s.nexts);
    assertEquals(0, s.errors);
    assertEquals(1, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void errorSignal(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signalError(new TestException());
    s.awaitError();
    assertEquals(0, s.nexts);
    assertEquals(1, s.errors);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void errorOnSubscribe(Executor executor) {
    var s = new TestSubscriber<Integer>();
    s.throwOnCall = true;
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitError();
    assertEquals(0, s.nexts);
    assertEquals(1, s.errors);
    assertEquals(0, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void oneItem(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    p.items.offer(1);
    p.complete();
    s.awaitComplete();
    assertEquals(1, s.nexts);
    assertEquals(1, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void oneItemThenError(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    p.items.offer(1);
    p.signalError(new TestException());
    s.awaitSubscribe();
    s.awaitError();
    assertTrue(s.nexts <= 1);
    assertEquals(1, s.errors);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void itemsAfterCancellation(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    p.submit(1);
    s.subscription.cancel();
    for (int i = 2; i <= 20; ++i)
      p.submit(i);
    p.complete();
    assertTrue(s.nexts < 20);
    assertEquals(0, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void errorOnNext(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    p.submit(1);
    s.throwOnCall = true;
    p.submit(2);
    p.complete();
    s.awaitComplete();
    assertEquals(1, s.errors);
    assertEquals(0, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void itemOrder(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    for (int i = 1; i <= 20; ++i)
      p.submit(i);
    p.complete();
    s.awaitComplete();
    assertEquals(20, s.nexts);
    assertEquals(20, s.items.peekLast());
    assertEquals(1, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void noItemsWithoutRequests(Executor executor) {
    var s = new TestSubscriber<Integer>();
    s.request = 0L;
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    p.submit(1);
    p.submit(2);
    assertEquals(0, s.nexts);
    s.subscription.request(3);
    p.submit(3);
    p.complete();
    s.awaitComplete();
    assertEquals(3, s.nexts);
    assertEquals(1, s.completes);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void requestOneReceiveOne(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe(); // requests 1
    s.request = 0L;
    p.submit(1);
    p.submit(2);
    p.complete();
    s.awaitNext(1);
    assertEquals(1, s.nexts);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void negativeRequest(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    s.subscription.request(-1L);
    p.submit(1);
    p.submit(2);
    p.complete();
    s.awaitError();
    assertEquals(1, s.errors);
    assertTrue(s.lastError instanceof IllegalArgumentException);
  }

  /**
   * Tests scenario for
   * JDK-8187947: A race condition in SubmissionPublisher
   */
  @ExecutorParameterizedTest
  @ExecutorConfig
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
    var pub = new SubmittableSubscription<>(new Sub(), executor);
    ref.set(pub);
    pub.signal(true);
    CompletableFuture.runAsync(() -> pub.submit(Boolean.TRUE)).get(20, TimeUnit.SECONDS);
    assertTrue(finished.await(20, TimeUnit.SECONDS));
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void abort_byCancellation(Executor executor) {
    var p = subscription(new TestSubscriber<>(), executor);
    p.cancel();
    p.cancel();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void abort_byCompletion(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    p.submit(1);
    p.complete();
    s.awaitComplete();
    s.subscription.cancel();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertFalse(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void abort_byError(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    p.submit(1);
    p.signalError(new TestException());
    s.awaitError();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertFalse(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void abort_byErrorOnSubscribe(Executor executor) {
    var s = new TestSubscriber<Integer>();
    s.throwOnCall = true;
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitError();
    p.awaitAbort();
    assertEquals(1, s.errors);
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void abort_byErrorOnNext(Executor executor) {
    var s = new TestSubscriber<Integer>();
    var p = subscription(s, executor);
    p.signal(true);
    s.awaitSubscribe();
    s.throwOnCall = true;
    p.submit(1);
    p.complete();
    s.awaitError();
    p.awaitAbort();
    assertEquals(1, s.errors);
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void rejectedExecution() {
    Executor busy = r -> { throw new RejectedExecutionException(); };
    var p = new SubmittableSubscription<>(new TestSubscriber<Integer>(), busy);
    assertThrows(RejectedExecutionException.class, () -> p.signal(true));
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @ExecutorParameterizedTest
  @ExecutorConfig
  void multipleErrors(Executor executor) {
    var s = new TestSubscriber<Integer>();
    s.throwOnCall = true;
    var p = subscription(s, executor);
    p.signalError(new TestException());
    s.awaitError();
    assertEquals(1, s.errors);
    assertEquals(1, s.lastError.getSuppressed().length);
  }

  /** Test that emit() stops in case of an asynchronous signalError detected by submitOnNext. */
  @ExecutorParameterizedTest
  @ExecutorConfig
  void pendingErrorStopsSubmission(Executor executor) {
    var awaitSignalError = new CountDownLatch(1);
    var awaitFirstOnNext = new CountDownLatch(1);
    var s = new TestSubscriber<Integer>() {
      @Override
      public void onNext(Integer item) {
        awaitFirstOnNext.countDown();
        awaitUninterruptibly(awaitSignalError);
        super.onNext(item);
      }
    };
    var p = subscription(s, executor);
    s.request = 0L;
    p.signal(true);
    // make 2 items available
    p.items.offer(1);
    p.items.offer(1);
    // request these 2 (async to not block if this test is not with an async executor)
    s.awaitSubscribe();
    CompletableFuture.runAsync(() -> s.subscription.request(2L));
    // wait till first onNext comes
    awaitUninterruptibly(awaitFirstOnNext);
    // set pendingError (first onNext now blocking)
    p.signalError(new TestException());
    // let first onNext pass
    awaitSignalError.countDown();
    s.awaitError();
    // second onNext shouldn't be called!
    assertEquals(1, s.nexts);
  }

  private SubmittableSubscription<Integer> subscription(
      Subscriber<? super Integer> downstream, Executor executor) {
    return new SubmittableSubscription<>(downstream, executor);
  }

  // Minimal AbstractSubscription implementation that allows item submission
  private static final class SubmittableSubscription<T> extends AbstractSubscription<T> {

    final ConcurrentLinkedQueue<T> items;
    volatile boolean complete;
    volatile int aborts;
    volatile boolean flowInterrupted;

    SubmittableSubscription(Subscriber<? super T> downstream, Executor executor) {
      super(downstream, executor);
      items = new ConcurrentLinkedQueue<>();
    }

    @Override
    protected long emit(Subscriber<? super T> downstream, long emit) {
      for (long c = 0L; ; c++) {
        if (items.isEmpty() && complete) { // end of source
          cancelOnComplete(downstream);
          return 0;
        } else if (items.isEmpty() || c >= emit) { // exhausted source or demand
          return c;
        } else if (!submitOnNext(downstream, items.poll())) {
          return 0;
        }
      }
    }

    @Override
    protected synchronized void abort(boolean flowInterrupted) {
      if (aborts++ == 0) {
        this.flowInterrupted = flowInterrupted;
      }
      notifyAll();
    }

    synchronized void awaitAbort() {
      while (aborts == 0) {
        try {
          wait();
        } catch (InterruptedException e) {
          fail(e);
        }
      }
    }

    void submit(T item) {
      items.offer(item);
      signal(false);
    }

    void complete() {
      complete = true;
      signal(true);
    }
  }
}
