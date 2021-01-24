/*
 * Copyright (c) 2019, 2020 Moataz Abdelnasser
 * Permission is hereby granted, free of charge, to any person obtaining a copy
 * of this software and associated documentation files (the "Software"), to deal
 * in the Software without restriction, including without limitation the rights
 * to use, copy, modify, merge, publish, distribute, sublicense, and/or sell
 * copies of the Software, and to permit persons to whom the Software is
 * furnished to do so, subject to the following conditions:
 * The above copyright notice and this permission notice shall be included in all
 * copies or substantial portions of the Software.
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND, EXPRESS OR
 * IMPLIED, INCLUDING BUT NOT LIMITED TO THE WARRANTIES OF MERCHANTABILITY,
 * FITNESS FOR A PARTICULAR PURPOSE AND NONINFRINGEMENT. IN NO EVENT SHALL THE
 * AUTHORS OR COPYRIGHT HOLDERS BE LIABLE FOR ANY CLAIM, DAMAGES OR OTHER
 * LIABILITY, WHETHER IN AN ACTION OF CONTRACT, TORT OR OTHERWISE, ARISING FROM,
 * OUT OF OR IN CONNECTION WITH THE SOFTWARE OR THE USE OR OTHER DEALINGS IN THE
 * SOFTWARE.
 */

package com.github.mizosoft.methanol.internal.cache;

import static com.github.mizosoft.methanol.testutils.TestUtils.awaitUninterruptibly;
import static org.junit.jupiter.api.Assertions.assertAll;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.github.mizosoft.methanol.testutils.MockExecutor;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class ExecutorServiceAdapterTest {
  @Test
  void adaptOnlyWrapsIfNotExecutorService() {
    var service = Executors.newCachedThreadPool();
    assertSame(service, ExecutorServiceAdapter.adapt(service));
  }

  @Test
  void execution() throws Exception {
    var recorder = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(recorder);

    var calls = new AtomicInteger();
    var future1 = service.submit(calls::incrementAndGet);
    var future2 = service.submit(calls::incrementAndGet);
    var future3 = service.submit(calls::incrementAndGet);
    assertEquals(3, recorder.taskCount());

    recorder.runAll();
    assertEquals(1, future1.get());
    assertEquals(2, future2.get());
    assertEquals(3, future3.get());
    assertEquals(3, calls.get());
  }

  @Test
  void shutdown() {
    var recorder = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(recorder);

    var calls = new AtomicInteger();
    service.execute(calls::incrementAndGet);
    service.shutdown();
    assertTrue(service.isShutdown());
    assertThrows(RejectedExecutionException.class, () -> service.execute(calls::incrementAndGet));
    // There's still one task "running" so the service is not terminated
    assertFalse(service.isTerminated());

    recorder.runAll();
    assertEquals(1, calls.get());
    assertTrue(service.isTerminated());
  }

  @Test
  void termination() throws InterruptedException {
    var threadPool = Executors.newCachedThreadPool();
    var service = new ExecutorServiceAdapter(threadPool);

    // Run threadCount waiting tasks
    int threadCount = 5;
    var beginLatch = new CountDownLatch(1);
    var calls = new AtomicInteger();
    for (int i = 0; i < threadCount; i++) {
      service.execute(() -> {
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
    assertTrue(service.awaitTermination(10, TimeUnit.SECONDS));
    assertEquals(5, calls.get());
    assertTrue(service.isTerminated());

    // Make sure awaitTermination now returns true immediately
    assertTrue(service.awaitTermination(0, TimeUnit.SECONDS));
    long start = System.nanoTime();
    assertTrue(service.awaitTermination(5, TimeUnit.SECONDS));
    long waitedSecs = TimeUnit.NANOSECONDS.toSeconds(System.nanoTime() - start);
    assertTrue(
        waitedSecs < 5,
        "waited for termination " + waitedSecs + " seconds after pool is already terminated");

    threadPool.shutdown();
  }

  @Test
  void shutdownNowRunningTasks() throws InterruptedException {
    var threadPool = Executors.newCachedThreadPool();
    var service = new ExecutorServiceAdapter(threadPool);

    // Run nonWaiterTaskCount immediate tasks
    // and waiterTaskCount waiting tasks (destined for interruption)
    int nonWaiterTaskCount = 2;
    int waiterTaskCount = 4;
    var nonWaiterEndLatch = new CountDownLatch(nonWaiterTaskCount);
    // Record the threads of finished tasks to make sure they're not
    // interrupted later by shutdownNow
    var nonWaiterThreads = ConcurrentHashMap.<Thread>newKeySet();
    var waiterArrivalLatch = new CountDownLatch(waiterTaskCount);
    var interruptedCalls = new AtomicInteger();
    for (int i = 0; i < nonWaiterTaskCount; i++) {
      service.execute(() -> {
        nonWaiterThreads.add(Thread.currentThread());
        nonWaiterEndLatch.countDown();
      });
    }
    for (int i = 0; i < waiterTaskCount; i++) {
      service.execute(() -> {
        assertThrows(InterruptedException.class, () -> {
          waiterArrivalLatch.countDown();
          TimeUnit.SECONDS.sleep(120);
        });
        interruptedCalls.incrementAndGet();
      });
    }

    assertTrue(nonWaiterEndLatch.await(10, TimeUnit.SECONDS));
    assertEquals(nonWaiterTaskCount, nonWaiterThreads.size());

    // Await waiting tasks' arrival
    waiterArrivalLatch.await();
    service.shutdownNow();
    assertTrue(service.isShutdown());
    assertTrue(service.awaitTermination(10, TimeUnit.SECONDS));
    // Already completed threads are not interrupted
    assertAll(nonWaiterThreads.stream().map(t -> () -> assertFalse(t.isInterrupted())));
    // All waiterTaskCount are interrupted
    assertEquals(waiterTaskCount, interruptedCalls.get());

    threadPool.shutdown();
  }

  @Test
  void shutdownNowQueuedTasks() {
    var recorder = new MockExecutor();
    var service = ExecutorServiceAdapter.adapt(recorder);

    int queuedTaskCount = 5;
    var calls = new AtomicInteger();
    for (int i = 0; i < queuedTaskCount; i++) {
      // Queue in recorder
      service.execute(calls::incrementAndGet);
    }
    assertEquals(queuedTaskCount, recorder.taskCount());

    var tasks = service.shutdownNow();
    assertEquals(queuedTaskCount, tasks.size());
    assertTrue(service.isTerminated());

    recorder.runAll();
    // Make sure the service skips queued tasks executed after shutdownNow
    assertEquals(0, calls.get());
  }
}