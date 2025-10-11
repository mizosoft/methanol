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

import static java.util.Objects.requireNonNull;

import com.github.mizosoft.methanol.testing.ExecutorExtension.ExecutorType;
import java.lang.System.Logger;
import java.lang.System.Logger.Level;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Executor;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

/** Creates and manages {@link Executor} instances. */
public final class ExecutorContext implements AutoCloseable {
  private static final Logger logger = System.getLogger(ExecutorContext.class.getName());

  private final String name;
  private final List<Executor> executors = new ArrayList<>();
  private final CloseableCopyOnWriteList<Throwable> uncaughtExceptions =
      new CloseableCopyOnWriteList<>();

  public ExecutorContext() {
    this("unnamed");
  }

  public ExecutorContext(String name) {
    this.name = requireNonNull(name);
  }

  public Executor createExecutor(ExecutorType type) {
    var executor =
        type.createExecutor(
            r ->
                new Thread(
                    () -> {
                      try {
                        r.run();
                      } catch (Throwable t) {
                        if (!uncaughtExceptions.add(t)) {
                          logger.log(
                              Level.ERROR, "Uncaught exception during asynchronous execution", t);
                        }
                      }
                    }));
    executors.add(executor);
    return executor;
  }

  @Override
  public void close() throws Exception {
    var exceptions = new ArrayList<Throwable>();
    for (var executor : executors) {
      if (executor instanceof ExecutorService) {
        var service = (ExecutorService) executor;
        service.shutdown();

        int gracefulShutdownSeconds = Math.max(1, TestUtils.TIMEOUT_SECONDS / 2);
        if (!awaitTermination(service, gracefulShutdownSeconds)) {
          service.shutdownNow(); // Interrupt workers.
          if (!awaitTermination(service, TestUtils.TIMEOUT_SECONDS)) {
            exceptions.add(
                new TimeoutException("Timed out while waiting for pool termination: " + executor));
          }
        }
      }
    }
    executors.clear();

    exceptions.addAll(uncaughtExceptions.close());
    if (!exceptions.isEmpty()) {
      throw new AggregateException(
          "Multiple exceptions while closing executors for (" + name + ")", exceptions);
    }
  }

  private static boolean awaitTermination(ExecutorService executor, int timeoutSeconds)
      throws InterruptedException {
    // Clear interruption flag to not throw from awaitTermination if this thread is interrupted
    // by some test.
    boolean interrupted = Thread.interrupted();
    try {
      return executor.awaitTermination(timeoutSeconds, TimeUnit.SECONDS);
    } finally {
      if (interrupted) {
        Thread.currentThread().interrupt();
      }
    }
  }
}
