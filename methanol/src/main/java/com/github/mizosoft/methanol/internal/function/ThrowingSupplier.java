package com.github.mizosoft.methanol.internal.function;

import java.util.concurrent.CompletionException;
import java.util.function.Supplier;

/** A {@code Supplier} that may throw a checked exception. */
@FunctionalInterface
public interface ThrowingSupplier<T> {
  T get() throws Exception;

  default Supplier<T> toUnchecked() {
    return () -> {
      try {
        return get();
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }
}
