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

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.junit.jupiter.api.Assertions.fail;

import com.github.mizosoft.methanol.testutils.TestException;
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
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;

@Timeout(value = 20)
class AbstractSubscriptionTest {

  // Overridden by AbstractSubscriptionTestWithExecutor for async version
  Executor executor() {
    return FlowSupport.SYNC_EXECUTOR;
  }

  @Test
  void subscribeNoSignals() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    s.awaitSubscribe();
    assertNotNull(s.sn);
    assertEquals(0, s.nexts);
    assertEquals(0, s.errors);
    assertEquals(0, s.completes);
  }

  @Test
  void noItems() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    p.complete();
    s.awaitComplete();
    assertEquals(0, s.nexts);
    assertEquals(0, s.errors);
    assertEquals(1, s.completes);
  }

  @Test
  void errorSignal() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signalError(new TestException());
    s.awaitError();
    assertEquals(0, s.nexts);
    assertEquals(1, s.errors);
  }

  @Test
  void errorOnSubscribe() {
    var s = new TestSubscriber();
    s.throwOnCall = true;
    var p = subscription(s);
    p.signal(true);
    s.awaitError();
    assertEquals(0, s.nexts);
    assertEquals(1, s.errors);
    assertEquals(0, s.completes);
  }

  @Test
  void oneItem() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    p.items.offer(1);
    p.complete();
    s.awaitComplete();
    assertEquals(1, s.nexts);
    assertEquals(1, s.completes);
  }

  @Test
  void onItemThenError() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    p.items.offer(1);
    p.signalError(new TestException());
    s.awaitSubscribe();
    s.awaitError();
    assertTrue(s.nexts <= 1);
    assertEquals(1, s.errors);
  }

  @Test
  void itemsAfterCancellation() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    s.awaitSubscribe();
    p.submit(1);
    s.sn.cancel();
    for (int i = 2; i <= 20; ++i)
      p.submit(i);
    p.complete();
    assertTrue(s.nexts < 20);
    assertEquals(0, s.completes);
  }

  @Test
  void errorOnNext() {
    var s = new TestSubscriber();
    var p = subscription(s);
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

  @Test
  void itemOrder() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    for (int i = 1; i <= 20; ++i)
      p.submit(i);
    p.complete();
    s.awaitComplete();
    assertEquals(20, s.nexts);
    assertEquals(20, s.last);
    assertEquals(1, s.completes);
  }

  @Test
  void noItemsWithoutRequests() {
    var s = new TestSubscriber();
    s.request = false;
    var p = subscription(s);
    p.signal(true);
    s.awaitSubscribe();
    p.submit(1);
    p.submit(2);
    assertEquals(0, s.nexts);
    s.sn.request(3);
    p.submit(3);
    p.complete();
    s.awaitComplete();
    assertEquals(3, s.nexts);
    assertEquals(1, s.completes);
  }

  @Test
  void requestOneReceiveOne() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    s.awaitSubscribe(); // requests 1
    s.request = false;
    p.submit(1);
    p.submit(2);
    p.complete();
    s.awaitNext(1);
    assertEquals(1, s.nexts);
  }

  @Test
  void negativeRequest() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    s.awaitSubscribe();
    s.sn.request(-1L);
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
  @Test
  void testMissedSignal_8187947() throws Exception {
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
    var pub = new SubmittableSubscription<>(new Sub(), executor());
    ref.set(pub);
    pub.signal(true);
    CompletableFuture.runAsync(() -> pub.submit(Boolean.TRUE)).get(20, TimeUnit.SECONDS);
    finished.await(20, TimeUnit.SECONDS);
  }

  @Test
  void abort_byCancellation() {
    var p = subscription(new TestSubscriber());
    p.cancel();
    p.cancel();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @Test
  void abort_byCompletion() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    p.submit(1);
    p.complete();
    s.awaitComplete();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertFalse(p.flowInterrupted);
  }

  @Test
  void abort_byError() {
    var s = new TestSubscriber();
    var p = subscription(s);
    p.signal(true);
    p.submit(1);
    p.signalError(new TestException());
    s.awaitError();
    p.awaitAbort();
    assertEquals(1, p.aborts);
    assertFalse(p.flowInterrupted);
  }

  @Test
  void abort_byErrorOnSubscribe() {
    var s = new TestSubscriber();
    s.throwOnCall = true;
    var p = subscription(s);
    p.signal(true);
    s.awaitError();
    p.awaitAbort();
    assertEquals(1, s.errors);
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @Test
  void abort_byErrorOnNext() {
    var s = new TestSubscriber();
    var p = subscription(s);
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

  @Test
  void rejectedExecution() {
    Executor busy = r -> { throw new RejectedExecutionException(); };
    var p = new SubmittableSubscription<>(new TestSubscriber(), busy);
    assertThrows(RejectedExecutionException.class, () -> p.signal(true));
    assertEquals(1, p.aborts);
    assertTrue(p.flowInterrupted);
  }

  @Test
  void multipleErrors() {
    var s = new TestSubscriber();
    s.throwOnCall = true;
    var p = subscription(s);
    p.signalError(new TestException());
    s.awaitError();
    assertEquals(1, s.errors);
    assertEquals(1, s.lastError.getSuppressed().length);
  }

  private SubmittableSubscription<Integer> subscription(
      Subscriber<? super Integer> downstream) {
    return new SubmittableSubscription<>(downstream, executor());
  }

  private static class TestSubscriber implements Subscriber<Integer> {

    volatile Subscription sn;
    int last;  // Requires that onNexts are in numeric order
    volatile int nexts;
    volatile int errors;
    volatile int completes;
    volatile boolean throwOnCall = false;
    volatile boolean request = true;
    volatile Throwable lastError;

    public synchronized void onSubscribe(Subscription s) {
      assertNull(sn);
      sn = s;
      notifyAll();
      if (throwOnCall)
        throw new TestException();
      if (request)
        sn.request(1L);
    }

    public synchronized void onNext(Integer t) {
      ++nexts;
      notifyAll();
      int current = t;
      assertTrue(current >= last);
      last = current;
      if (request)
        sn.request(1L);
      if (throwOnCall)
        throw new TestException();
    }

    public synchronized void onError(Throwable t) {
      assertEquals(0, completes);
      assertEquals(0, errors);
      lastError = t;
      ++errors;
      notifyAll();
    }

    public synchronized void onComplete() {
      assertEquals(0, completes);
      ++completes;
      notifyAll();
    }

    synchronized void awaitSubscribe() {
      while (sn == null) {
        try {
          wait();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }

    synchronized void awaitNext(int n) {
      while (nexts < n) {
        try {
          wait();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }

    synchronized void awaitComplete() {
      while (completes == 0 && errors == 0) {
        try {
          wait();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }

    synchronized void awaitError() {
      while (errors == 0) {
        try {
          wait();
        } catch (Exception ex) {
          throw new AssertionError(ex);
        }
      }
    }
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
    protected long emit(Subscriber<? super T> d, long e) {
      for (long c = 0L; ; c++) {
        if (items.isEmpty() && complete) { // end of source
          cancelOnComplete(d);
          return 0;
        } else if (items.isEmpty() || c >= e) { // exhausted source or demand
          return c;
        } else if (!submitOnNext(d, items.poll())) {
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
