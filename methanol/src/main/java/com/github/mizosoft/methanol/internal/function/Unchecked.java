/*
 * Copyright (c) 2024 Moataz Hussein
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

package com.github.mizosoft.methanol.internal.function;

import com.github.mizosoft.methanol.function.ThrowingConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/** Static functions that make it easier to mix checked exceptions with lambdas. */
public class Unchecked {
  private Unchecked() {}

  public static <T, R> Function<T, R> func(ThrowingFunction<T, R> func) {
    return func.toUnchecked();
  }

  public static <T> Consumer<T> consumer(ThrowingConsumer<T> consumer) {
    return consumer.toUnchecked();
  }

  public static <T> Supplier<T> supplier(ThrowingSupplier<T> supplier) {
    return supplier.toUnchecked();
  }

  public static Runnable runnable(ThrowingRunnable runnable) {
    return runnable.toUnchecked();
  }

  public static <T> CompletableFuture<T> supplyAsync(
      ThrowingSupplier<T> supplier, Executor executor) {
    return CompletableFuture.supplyAsync(supplier.toUnchecked(), executor);
  }

  public static CompletableFuture<Void> runAsync(ThrowingRunnable runnable, Executor executor) {
    return CompletableFuture.runAsync(runnable.toUnchecked(), executor);
  }

  public static void propagateIfUnchecked(Throwable t) {
    if (t instanceof RuntimeException) {
      throw (RuntimeException) t;
    } else if (t instanceof Error) {
      throw (Error) t;
    }
  }
}
