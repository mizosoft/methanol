package com.github.mizosoft.methanol.internal.function;

import java.util.concurrent.CompletionException;
import java.util.function.Function;

/** A {@code Function} that may throw a checked exception. */
@FunctionalInterface
public interface ThrowingFunction<T, R> {
  R apply(T val) throws Exception;

  default Function<T, R> toUnchecked() {
    return val -> {
      try {
        return apply(val);
      } catch (Exception e) {
        throw new CompletionException(e);
      }
    };
  }
}
