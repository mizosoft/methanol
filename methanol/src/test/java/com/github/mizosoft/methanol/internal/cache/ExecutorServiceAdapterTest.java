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

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testing.TestUtils.awaitUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.testing.ExecutorExtension;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorSpec;
import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import com.github.mizosoft.methanol.testing.MockExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

@ExtendWith(ExecutorExtension.class)
class ExecutorServiceAdapterTest {
  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void adaptOnlyWrapsIfNotExecutorService(ExecutorService service) {
    assertSame(service, ExecutorServiceAdapter.adapt(service));
  }

  @Test
  void execution() throws Exception {
    var executor = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(executor);

    var calls = new AtomicInteger();
    var future1 = service.submit(calls::incrementAndGet);
    var future2 = service.submit(calls::incrementAndGet);
    var future3 = service.submit(calls::incrementAndGet);
    assertEquals(3, executor.taskCount());

    executor.runAll();
    assertEquals(1, future1.get());
    assertEquals(2, future2.get());
    assertEquals(3, future3.get());
    assertEquals(3, calls.get());
  }

  @Test
  void shutdown() {
    var executor = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(executor);

    var calls = new AtomicInteger();
    service.execute(calls::incrementAndGet);
    service.shutdown();
    assertTrue(service.isShutdown());
    assertThrows(RejectedExecutionException.class, () -> service.execute(calls::incrementAndGet));

    // There's still one task "running" so the service is not terminated
    assertFalse(service.isTerminated());

    executor.runAll();
    assertEquals(1, calls.get());
    assertTrue(service.isTerminated());
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void termination(Executor threadPool) throws InterruptedException {
    var service = new ExecutorServiceAdapter(threadPool);

    // Run threadCount waiting tasks
    int threadCount = 5;
    var beginLatch = new CountDownLatch(1);
    var calls = new AtomicInteger();
    for (int i = 0; i < threadCount; i++) {
      service.execute(
          () -> {
            awaitUninterruptibly(beginLatch);
            calls.incrementAndGet();
          });
    }

    service.shutdown();
    assertTrue(service.isShutdown());

    // We're not terminated yet
    assertFalse(service.isTerminated());
    assertFalse(service.awaitTermination(0, TimeUnit.SECONDS));

    // Run waiting tasks and wait for them to complete with awaitTermination
    beginLatch.countDown();
    assertTrue(service.awaitTermination(20, TimeUnit.SECONDS));
    assertEquals(threadCount, calls.get());
    assertTrue(service.isTerminated());

    // Make sure awaitTermination now returns true immediately
    assertTrue(service.awaitTermination(0, TimeUnit.SECONDS));
    long start = System.nanoTime();
    assertTrue(service.awaitTermination(5, TimeUnit.SECONDS));
    long waitedSecs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    assertTrue(
        waitedSecs < 5,
        "waited for termination " + waitedSecs + " seconds after pool is already terminated");
  }

  @Test
  @ExecutorSpec(ExecutorType.CACHED_POOL)
  void shutdownNowRunningTasks(Executor threadPool) throws InterruptedException {
    var service = new ExecutorServiceAdapter(threadPool);

    // Run nonWaiterTaskCount immediate tasks
    // and waiterTaskCount waiting tasks (destined for interruption)
    int nonWaiterTaskCount = 2;
    int waiterTaskCount = 4;
    var nonWaitersExitLatch = new CountDownLatch(nonWaiterTaskCount);
    // Record the threads of finished tasks to assert they're not interrupted by shutdownNow
    var nonWaiterThreads = ConcurrentHashMap.<Thread>newKeySet();
    var waitersArrivalLatch = new CountDownLatch(waiterTaskCount);
    var waitersExitLatch = new CountDownLatch(waiterTaskCount);
    var interruptedCalls = new AtomicInteger();
    for (int i = 0; i < nonWaiterTaskCount; i++) {
      service.execute(
          () -> {
            nonWaiterThreads.add(Thread.currentThread());
            nonWaitersExitLatch.countDown();
          });
    }
    var toBeInterruptedLatch = new CountDownLatch(1);
    for (int i = 0; i < waiterTaskCount; i++) {
      service.execute(
          () -> {
            waitersArrivalLatch.countDown();
            try {
              assertThrows(InterruptedException.class, toBeInterruptedLatch::await);
              interruptedCalls.incrementAndGet();
            } finally {
              waitersExitLatch.countDown();
            }
          });
    }

    try {
      assertTrue(nonWaitersExitLatch.await(20, TimeUnit.SECONDS));

      // Await waiter tasks arrival then interrupted them by shutdown
      assertTrue(waitersArrivalLatch.await(20, TimeUnit.SECONDS));
      service.shutdownNow();
      assertTrue(service.isShutdown());
      assertTrue(service.awaitTermination(20, TimeUnit.SECONDS));

      // Already completed threads are not interrupted
      assertAll(nonWaiterThreads.stream().map(t -> () -> assertFalse(t.isInterrupted())));

      // All waiter tasks are interrupted
      assertTrue(waitersExitLatch.await(20, TimeUnit.SECONDS));
      assertEquals(waiterTaskCount, interruptedCalls.get());
    } finally {
      // Make sure waiter tasks exit regardless
      toBeInterruptedLatch.countDown();
    }
  }

  @Test
  void shutdownNowQueuedTasks() {
    var executor = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(executor);

    int queuedTaskCount = 5;
    var calls = new AtomicInteger();
    for (int i = 0; i < queuedTaskCount; i++) {
      // Queue in executor
      service.execute(calls::incrementAndGet);
    }
    assertEquals(queuedTaskCount, executor.taskCount());

    var tasks = service.shutdownNow();
    assertEquals(queuedTaskCount, tasks.size());
    assertTrue(service.isTerminated());

    // Make sure the service skips queued tasks executed after shutdownNow
    executor.runAll();
    assertEquals(0, calls.get());
  }
}
