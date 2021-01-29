package com.github.mizosoft.methanol.internal.function;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** Static functions that make it less painful to mix checked exceptions with lambdas. */
public class Unchecked {
  private Unchecked() {}

  public static <T, R> Function<T, R> func(ThrowingFunction<T, R> func) {
    return func.toUnchecked();
  }

  public static <T> CompletableFuture<T> supplyAsync(
      ThrowingSupplier<T> supplier, Executor executor) {
    return CompletableFuture.supplyAsync(supplier.toUnchecked(), executor);
  }

  public static CompletableFuture<Void> runAsync(ThrowingRunnable runnable, Executor executor) {
    return CompletableFuture.runAsync(runnable.toUnchecked(), executor);
  }
}
