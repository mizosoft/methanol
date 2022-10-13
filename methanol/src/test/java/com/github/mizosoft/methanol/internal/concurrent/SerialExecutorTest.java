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

package com.github.mizosoft.methanol.internal.concurrent;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType.CACHED_POOL;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.testing.MockExecutor;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import java.util.ArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.Collectors;
import java.util.stream.IntStream;
import org.checkerframework.checker.nullness.qual.MonotonicNonNull;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.RepeatedTest;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

class SerialExecutorTest {
  private @MonotonicNonNull MockExecutor mockExecutor;
  private @MonotonicNonNull SerialExecutor executor;

  @BeforeEach
  void setUp() {
    mockExecutor = new MockExecutor();
    executor = new SerialExecutor(mockExecutor);
  }

  @Test
  void sequentialExecution() {
    var calls = new AtomicInteger();
    Runnable incrementTask = () -> {
      calls.incrementAndGet();
      // There shouldn't be any awaiting drain tasks
      assertFalse(mockExecutor.hasNext());
    };

    executor.execute(incrementTask);
    assertEquals(1, mockExecutor.taskCount());
    executor.execute(incrementTask);
    // SerialExecutor's drain task is only submitted once
    assertEquals(1, mockExecutor.taskCount());
    // Nothing is run so far
    assertEquals(0, calls.get());
    // Run drain task -> all submitted incrementTasks run
    mockExecutor.runNext();
    assertEquals(2, calls.get());
    assertFalse(mockExecutor.hasNext());

    for (int i = 0; i < 10; i++) {
      executor.execute(incrementTask);
    }
    assertEquals(1, mockExecutor.taskCount());
    mockExecutor.runNext();
    assertEquals(12, calls.get());
  }

  @Test
  void executionOrder() {
    var order = new ArrayList<Integer>();
    for (int i = 0; i < 10; i++) {
      int val = i; // Can't use i in lambda
      executor.execute(() -> order.add(val));
    }
    mockExecutor.runNext();

    var expectedOrder = IntStream.range(0, 10).boxed().collect(Collectors.toUnmodifiableList());
    assertEquals(expectedOrder, order);
  }

  @Test
  void rejectExecution() {
    var calls = new AtomicInteger();

    mockExecutor.reject(true);
    assertThrows(RejectedExecutionException.class, () -> executor.execute(calls::incrementAndGet));
    assertEquals(0, mockExecutor.taskCount());

    mockExecutor.reject(false);
    executor.execute(calls::incrementAndGet);
    assertEquals(1, mockExecutor.taskCount());

    mockExecutor.reject(true);
    // No drain task is submitted to the delegate so nothing is rejected
    executor.execute(calls::incrementAndGet);
    assertEquals(1, mockExecutor.taskCount());

    mockExecutor.runNext();
    // The first rejected task is not retained, so there are only 2 increments
    assertEquals(2, calls.get());
  }

  @Test
  void shutdown() {
    var calls = new AtomicInteger();

    executor.execute(calls::incrementAndGet);
    assertEquals(1, mockExecutor.taskCount());
    executor.shutdown();
    assertThrows(RejectedExecutionException.class, () -> executor.execute(calls::incrementAndGet));

    // Shutdown doesn't prevent already scheduled drain from running
    mockExecutor.runNext();
    assertEquals(1, calls.get());
  }

  @Test
  void throwFromTask() {
    var calls = new AtomicInteger();
    Runnable saneTask = calls::incrementAndGet;
    Runnable crazyTask = () -> {
      calls.incrementAndGet();
      throw new TestException();
    };

    executor.execute(saneTask);
    executor.execute(crazyTask);
    executor.execute(saneTask);
    // Drain task is submitted
    assertEquals(1, mockExecutor.taskCount());

    // saneTask runs then exception propagates from crazyTask
    // to delegate executor's thread (current thread)
    assertThrows(TestException.class, mockExecutor::runNext);
    assertEquals(2, calls.get());
    // Drain task is retried
    assertTrue(mockExecutor.awaitNext(10, TimeUnit.SECONDS), "drain task wasn't retried in 10 secs");
    assertEquals(1, mockExecutor.taskCount());

    mockExecutor.runNext();
    assertEquals(3, calls.get());
  }

  /** See javadoc of {@link SerialExecutor#sync}. */
  @Test
  void submissionABA() {
    Executor sameThreadExecutor = r -> {
      assertFalse(executor.isRunningBitSet());
      r.run();
      assertFalse(executor.isRunningBitSet());
    };
    executor = new SerialExecutor(sameThreadExecutor);

    var calls = new AtomicInteger();
    Runnable incrementTask = () -> {
      calls.incrementAndGet();
      // Make sure the drain task sets the RUNNING bit
      assertTrue(executor.isRunningBitSet());
    };

    executor.execute(incrementTask);
    assertEquals(1, calls.get());
    assertEquals(1, executor.drainCount());
    assertFalse(executor.isSubmittedBitSet());
    executor.execute(incrementTask);
    assertEquals(2, calls.get());
    assertEquals(2, executor.drainCount());
    assertFalse(executor.isSubmittedBitSet());
  }

  @RepeatedTest(10)
  @ExtendWith(ExecutorExtension.class)
  @ExecutorConfig(CACHED_POOL)
  void executionFromMultipleThreads(Executor threadPool) throws InterruptedException {
    executor = new SerialExecutor(threadPool);

    var stochasticLatch = new Object() {
      private final AtomicBoolean acquired = new AtomicBoolean();

      void acquire() {
        assertTrue(acquired.compareAndSet(false, true), "concurrent access detected");
      }

      void release() {
        assertTrue(acquired.compareAndSet(true, false), "concurrent access detected");
      }
    };

    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var endLatch = new CountDownLatch(threadCount);
    var calls = new AtomicInteger();
    for (int i = 0; i < threadCount; i++) {
      threadPool.execute(() -> {
        awaitUninterruptibly(arrival);
        executor.execute(() -> {
          stochasticLatch.acquire();
          try {
            calls.incrementAndGet();
          } finally {
            stochasticLatch.release();
            endLatch.countDown();
          }
        });
      });
    }

    assertTrue(endLatch.await(10, TimeUnit.SECONDS));
    assertEquals(threadCount, calls.get());
  }

  @Test
  void incrementingDrainCountMaintainsStateBits() {
    executor.execute(() -> {});
    assertEquals(1, mockExecutor.taskCount());

    // Set SHUTDOWN bit
    executor.shutdown();
    assertTrue(executor.isShutdownBitSet());

    // Execute drain to increment drain count
    mockExecutor.runNext();
    assertEquals(1, executor.drainCount());

    // Shutdown bit isn't touched
    assertTrue(executor.isShutdownBitSet());
  }
}
