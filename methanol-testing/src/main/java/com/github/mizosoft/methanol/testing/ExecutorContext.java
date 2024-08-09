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

package com.github.mizosoft.methanol.testing;

import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

public final class ExecutorContext implements AutoCloseable {
  private final List<Executor> executors = new ArrayList<>();
  private final List<Throwable> asyncExceptions = new CopyOnWriteArrayList<>();

  public ExecutorContext() {}

  public Executor createExecutor(ExecutorType type) {
    var executor =
        type.createExecutor(
            r ->
                new Thread(
                    () -> {
                      try {
                        r.run();
                      } catch (Throwable t) {
                        asyncExceptions.add(t);
                      }
                    }));
    executors.add(executor);
    return executor;
  }

  @Override
  public void close() throws Exception {
    for (var executor : executors) {
      if (executor instanceof ExecutorService) {
        var service = (ExecutorService) executor;
        service.shutdown();

        // Clear interruption flag to not throw from awaitTermination if this thread is interrupted
        // by some test.
        boolean interrupted = Thread.interrupted();
        try {
          if (!service.awaitTermination(TestUtils.TIMEOUT_SECONDS, TimeUnit.SECONDS)) {
            throw new TimeoutException("Timed out while waiting for pool termination: " + executor);
          }
        } finally {
          if (interrupted) {
            Thread.currentThread().interrupt();
          }
        }
      }
    }

    executors.clear();

    var asyncExceptionsSnapshot = List.copyOf(asyncExceptions);
    if (!asyncExceptionsSnapshot.isEmpty()) {
      asyncExceptions.clear();
      throw new AggregateException("Uncaught asynchronous failures", asyncExceptionsSnapshot);
    }
  }
}
