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

package com.github.mizosoft.methanol.internal.concurrent;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUnchecked;
import static com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorType.CACHED_POOL;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.junit.jupiter.api.Assertions.assertAll;

import com.github.mizosoft.methanol.testing.MockExecutor;
import com.github.mizosoft.methanol.testing.TestException;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension;
import com.github.mizosoft.methanol.testing.junit.ExecutorExtension.ExecutorConfig;
import java.util.ArrayList;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executor;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
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
    Runnable incrementTask =
        () -> {
          calls.incrementAndGet();
          // There shouldn't be any awaiting drain tasks.
          assertThat(mockExecutor.hasNext()).isFalse();
        };

    executor.execute(incrementTask);
    assertThat(mockExecutor.taskCount()).isOne();

    // SerialExecutor's drain task is only submitted once.
    executor.execute(incrementTask);
    assertThat(mockExecutor.taskCount()).isOne();

    // Nothing is run so far.
    assertThat(calls).hasValue(0);

    // Run drain task -> all submitted incrementTasks run.
    mockExecutor.runNext();
    assertThat(calls).hasValue(2);
    assertThat(mockExecutor.hasNext()).isFalse();

    for (int i = 0; i < 10; i++) {
      executor.execute(incrementTask);
    }
    assertThat(mockExecutor.taskCount()).isOne();
    mockExecutor.runNext();
    assertThat(calls).hasValue(12);
  }

  @Test
  void executionOrder() {
    var order = new ArrayList<Integer>();
    for (int i = 0; i < 10; i++) {
      int val = i; // Can't use i in lambda.
      executor.execute(() -> order.add(val));
    }
    mockExecutor.runNext();
    assertThat(order).containsExactly(IntStream.range(0, 10).boxed().toArray(Integer[]::new));
  }

  @Test
  void rejectExecution() {
    var calls = new AtomicInteger();

    mockExecutor.reject(true);
    assertThatThrownBy(() -> executor.execute(calls::incrementAndGet))
        .isInstanceOf(RejectedExecutionException.class);
    assertThat(mockExecutor.taskCount()).isZero();

    mockExecutor.reject(false);
    executor.execute(calls::incrementAndGet);
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.reject(true);
    executor.execute(calls::incrementAndGet);
    assertThat(mockExecutor.taskCount()).isOne();

    // The first rejected task is not retained, so there are only 2 increments.
    mockExecutor.runNext();
    assertThat(calls).hasValue(2);
  }

  @Test
  void shutdown() {
    var calls = new AtomicInteger();

    executor.execute(calls::incrementAndGet);
    executor.shutdown();
    assertThatThrownBy(() -> executor.execute(calls::incrementAndGet))
        .isInstanceOf(RejectedExecutionException.class);

    // Shutdown doesn't prevent already scheduled drain from running.
    mockExecutor.runNext();
    assertThat(calls).hasValue(1);
  }

  @Test
  void throwFromTask() {
    var calls = new AtomicInteger();
    Runnable saneTask = calls::incrementAndGet;
    Runnable crazyTask =
        () -> {
          calls.incrementAndGet();
          throw new TestException();
        };

    executor.execute(saneTask);
    executor.execute(crazyTask);
    executor.execute(saneTask);

    // saneTask runs then exception propagates from crazyTask to delegate executor's thread (current
    // thread).
    assertThatThrownBy(mockExecutor::runNext).isInstanceOf(TestException.class);
    assertThat(calls).hasValue(2);

    // Drain task is retried.
    assertThat(mockExecutor.awaitNext(5, TimeUnit.SECONDS))
        .withFailMessage("drain task retry timed out")
        .isTrue();
    assertThat(mockExecutor.taskCount()).isOne();

    mockExecutor.runNext();
    assertThat(calls).hasValue(3);
  }

  @RepeatedTest(10)
  @ExtendWith(ExecutorExtension.class)
  @ExecutorConfig(CACHED_POOL)
  void executionFromMultipleThreads(Executor threadPool) {
    executor = new SerialExecutor(threadPool);

    var running = new AtomicBoolean();
    int threadCount = 10;
    var arrival = new CyclicBarrier(threadCount);
    var calls = new AtomicInteger();
    var futures = new ArrayList<CompletableFuture<Void>>();
    for (int i = 0; i < threadCount; i++) {
      var future = new CompletableFuture<Void>();
      futures.add(future);
      threadPool.execute(
          () -> {
            awaitUnchecked(arrival);
            future.completeAsync(
                () -> {
                  assertThat(running.compareAndSet(false, true))
                      .withFailMessage("concurrent access detected")
                      .isTrue();
                  try {
                    calls.incrementAndGet();
                  } finally {
                    assertThat(running.compareAndSet(true, false))
                        .withFailMessage("concurrent access detected")
                        .isTrue();
                  }
                  return null;
                },
                executor);
          });
    }

    assertAll(futures.stream().map(future -> future::join));
    assertThat(calls).hasValue(threadCount);
  }
}
