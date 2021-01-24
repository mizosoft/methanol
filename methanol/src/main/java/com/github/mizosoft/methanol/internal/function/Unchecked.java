package com.github.mizosoft.methanol.internal.function;

import com.github.mizosoft.methanol.internal.flow.FlowSupport;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.function.Function;
import java.util.function.Supplier;

/** Static functions that make it less painful to mix checked exceptions with lambdas. */
public class Unchecked {
  private Unchecked() {}

  public static <T> Supplier<T> supplier(ThrowingSupplier<T> supplier) {
    return supplier.toUnchecked();
  }

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

  public static CompletableFuture<Void> runNow(ThrowingRunnable runnable) {
    return runAsync(runnable, FlowSupport.SYNC_EXECUTOR);
  }

  public static <T> CompletableFuture<T> supplyNow(ThrowingSupplier<T> supplier) {
    return supplyAsync(supplier, FlowSupport.SYNC_EXECUTOR);
  }
}
