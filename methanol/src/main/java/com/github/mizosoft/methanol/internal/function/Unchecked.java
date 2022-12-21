package com.github.mizosoft.methanol.internal.function;

import com.github.mizosoft.methanol.function.ThrowingConsumer;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Consumer;
import java.util.function.Function;

/** Static functions that make it easier to mix checked exceptions with lambdas. */
public class Unchecked {
  private Unchecked() {}

  public static <T, R> Function<T, R> func(ThrowingFunction<T, R> func) {
    return func.toUnchecked();
  }

  public static <T> Consumer<T> consumer(ThrowingConsumer<T> consumer) {
    return consumer.toUnchecked();
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
